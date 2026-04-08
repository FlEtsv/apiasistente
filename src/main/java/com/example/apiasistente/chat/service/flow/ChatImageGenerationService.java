package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.auth.entity.AppUser;
import com.example.apiasistente.chat.config.ChatImageGenerationProperties;
import com.example.apiasistente.chat.dto.ChatMediaInput;
import com.example.apiasistente.chat.dto.ChatResponse;
import com.example.apiasistente.chat.entity.ChatSession;
import com.example.apiasistente.chat.service.ChatAuditTrailService;
import com.example.apiasistente.chat.service.ChatModelSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dedicated flow for image generation inside chat.
 */
@Service
public class ChatImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ChatImageGenerationService.class);

    private final ChatImageGenerationProperties properties;
    private final ChatModelSelector modelSelector;
    private final ChatSessionService sessionService;
    private final ChatHistoryService historyService;
    private final ChatImageGeneratorClient imageGeneratorClient;
    private final ChatGeneratedImageStoreService imageStoreService;
    private final ChatAuditTrailService auditTrailService;

    public ChatImageGenerationService(ChatImageGenerationProperties properties,
                                      ChatModelSelector modelSelector,
                                      ChatSessionService sessionService,
                                      ChatHistoryService historyService,
                                      ChatImageGeneratorClient imageGeneratorClient,
                                      ChatGeneratedImageStoreService imageStoreService,
                                      ChatAuditTrailService auditTrailService) {
        this.properties = properties;
        this.modelSelector = modelSelector;
        this.sessionService = sessionService;
        this.historyService = historyService;
        this.imageGeneratorClient = imageGeneratorClient;
        this.imageStoreService = imageStoreService;
        this.auditTrailService = auditTrailService;
    }

    @Transactional
    public ChatResponse generate(String username,
                                 String maybeSessionId,
                                 String userText,
                                 String requestedModel,
                                 String externalUserId,
                                 List<ChatMediaInput> media) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("La generacion de imagen esta desactivada.");
        }

        String prompt = normalizePrompt(userText);
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("El prompt de imagen no puede estar vacio.");
        }

        String normalizedExternalUserId = sessionService.normalizeExternalUserId(externalUserId);
        AppUser user = sessionService.requireUser(username);
        ChatSession session = sessionService.resolveSession(user, maybeSessionId, normalizedExternalUserId);
        sessionService.touchSession(session);
        sessionService.autoTitleIfDefault(session, prompt);
        historyService.saveUserMessage(session, prompt);

        String model = modelSelector.resolveImageModel(requestedModel);
        ReferenceImage referenceImage = resolveReferenceImage(media);
        boolean img2img = referenceImage != null;
        if (img2img) {
            log.info(
                    "chat_image_reference_detected sessionId={} name={} mimeType={} base64Chars={}",
                    session.getId(),
                    referenceImage.name(),
                    referenceImage.mimeType(),
                    referenceImage.base64() == null ? 0 : referenceImage.base64().length()
            );
        } else if (media != null && !media.isEmpty()) {
            log.info(
                    "chat_image_reference_not_found sessionId={} mediaCount={} reason=no_image_with_base64",
                    session.getId(),
                    media.size()
            );
        }
        log.info(
                "chat_image_generation_start sessionId={} model={} mode={} promptPreview={}",
                session.getId(),
                model,
                img2img ? "img2img" : "txt2img",
                preview(prompt)
        );

        auditTrailService.record("chat.image.start", imageStartPayload(
                session.getId(),
                model,
                requestedModel,
                prompt,
                img2img,
                referenceImage
        ));

        try {
            ChatImageGeneratorClient.GeneratedImage generated = imageGeneratorClient.generate(
                    prompt,
                    model,
                    img2img ? referenceImage.base64() : null
            );
            String effectiveModel = generated.checkpoint() == null || generated.checkpoint().isBlank()
                    ? model
                    : generated.checkpoint();
            String imageId = imageStoreService.store(session.getId(), generated.mimeType(), generated.bytes());
            String imageUrl = "/api/chat/sessions/%s/images/%s".formatted(session.getId(), imageId);
            String downloadUrl = "%s?download=1".formatted(imageUrl);

            String reply = """
                    Imagen generada con modelo `%s`.
                    Modo: **%s**

                    ![Imagen generada](%s)

                    [Descargar imagen](%s)

                    Prompt: %s
                    """.formatted(
                    effectiveModel,
                    img2img ? "Imagen -> imagen" : "Texto -> imagen",
                    imageUrl,
                    downloadUrl,
                    prompt
            );
            if (img2img) {
                reply = reply + "\nReferencia: `" + referenceImage.name() + "`";
            }
            if (generated.fallbackApplied()) {
                reply = reply + "\nNota: el modelo solicitado `"
                        + generated.requestedCheckpoint()
                        + "` no estaba cargado en ComfyUI y se uso `"
                        + effectiveModel
                        + "`.";
            }

            String genMetadata = buildImageGenMetadata(effectiveModel, requestedModel, img2img, imageId, generated);
            historyService.saveAssistantMessage(session, reply, genMetadata);
            sessionService.touchSession(session);
            log.info(
                    "chat_image_generation_done sessionId={} requestedModel={} effectiveModel={} fallback={} mode={} imageId={} mimeType={}",
                    session.getId(),
                    model,
                    effectiveModel,
                    generated.fallbackApplied(),
                    img2img ? "img2img" : "txt2img",
                    imageId,
                    generated.mimeType()
            );
            auditTrailService.record("chat.image.done", imageDonePayload(
                    session.getId(),
                    model,
                    effectiveModel,
                    generated.fallbackApplied(),
                    img2img,
                    imageId,
                    generated.mimeType()
            ));

            return new ChatResponse(
                    session.getId(),
                    reply,
                    List.of(),
                    true,
                    1.0,
                    0,
                    false,
                    false,
                    "LOW"
            );
        } catch (RuntimeException ex) {
            auditTrailService.record("chat.image.failed", imageFailedPayload(
                    session.getId(),
                    model,
                    requestedModel,
                    prompt,
                    img2img,
                    referenceImage,
                    ex
            ));
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public ChatGeneratedImageStoreService.StoredImage loadForUser(String username,
                                                                  String sessionId,
                                                                  String imageId) {
        AppUser user = sessionService.requireUser(username);
        sessionService.requireOwnedGenericSession(user, sessionId);
        return imageStoreService.load(sessionId, imageId);
    }

    /**
     * Construye metadata JSON para la respuesta de generacion de imagen.
     * Permite que el historial identifique este mensaje como una generacion y muestre el modelo/modo usados.
     */
    private String buildImageGenMetadata(String effectiveModel,
                                         String requestedModel,
                                         boolean img2img,
                                         String imageId,
                                         ChatImageGeneratorClient.GeneratedImage generated) {
        try {
            java.util.LinkedHashMap<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("type", "image_generation");
            meta.put("mode", img2img ? "img2img" : "txt2img");
            meta.put("effectiveModel", safe(effectiveModel));
            meta.put("requestedModel", safe(requestedModel));
            meta.put("imageId", safe(imageId));
            meta.put("mimeType", safe(generated.mimeType()));
            meta.put("fallbackApplied", generated.fallbackApplied());
            if (generated.fallbackApplied()) {
                meta.put("requestedCheckpoint", safe(generated.requestedCheckpoint()));
            }
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(meta);
        } catch (Exception ex) {
            return null;
        }
    }

    private String normalizePrompt(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("\\s+", " ").trim();
        int maxChars = Math.max(40, properties.getMaxPromptChars());
        if (clean.length() > maxChars) {
            clean = clean.substring(0, maxChars).trim();
        }
        return clean;
    }

    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String clean = value.replaceAll("\\s+", " ").trim();
        if (clean.length() <= 120) {
            return clean;
        }
        return clean.substring(0, 120).trim() + "...";
    }

    private ReferenceImage resolveReferenceImage(List<ChatMediaInput> media) {
        if (media == null || media.isEmpty()) {
            return null;
        }
        for (ChatMediaInput item : media) {
            if (item == null) {
                continue;
            }
            String mime = normalizeMime(item.getMimeType());
            if (mime == null || !mime.startsWith("image/")) {
                continue;
            }
            String cleanBase64 = sanitizeBase64(item.getBase64());
            if (cleanBase64.isBlank()) {
                continue;
            }
            String name = item.getName() == null || item.getName().isBlank() ? "referencia" : item.getName().trim();
            return new ReferenceImage(name, cleanBase64, mime);
        }
        return null;
    }

    private String normalizeMime(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.trim().toLowerCase();
        return clean.isBlank() ? null : clean;
    }

    private String sanitizeBase64(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.trim();
        int commaIndex = clean.indexOf(',');
        if (commaIndex >= 0) {
            clean = clean.substring(commaIndex + 1);
        }
        return clean.replaceAll("\\s+", "");
    }

    private record ReferenceImage(String name, String base64, String mimeType) {
    }

    private Map<String, Object> imageStartPayload(String sessionId,
                                                  String model,
                                                  String requestedModel,
                                                  String prompt,
                                                  boolean img2img,
                                                  ReferenceImage referenceImage) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", safe(sessionId));
        payload.put("resolvedModel", safe(model));
        payload.put("requestedModel", safe(requestedModel));
        payload.put("mode", img2img ? "img2img" : "txt2img");
        payload.put("referenceName", referenceImage == null ? "" : safe(referenceImage.name()));
        payload.put("referenceMimeType", referenceImage == null ? "" : safe(referenceImage.mimeType()));
        payload.put("promptPreview", auditTrailService.preview(prompt));
        return payload;
    }

    private Map<String, Object> imageDonePayload(String sessionId,
                                                 String model,
                                                 String effectiveModel,
                                                 boolean fallbackApplied,
                                                 boolean img2img,
                                                 String imageId,
                                                 String mimeType) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", safe(sessionId));
        payload.put("resolvedModel", safe(model));
        payload.put("effectiveModel", safe(effectiveModel));
        payload.put("fallbackApplied", fallbackApplied);
        payload.put("mode", img2img ? "img2img" : "txt2img");
        payload.put("imageId", safe(imageId));
        payload.put("mimeType", safe(mimeType));
        return payload;
    }

    private Map<String, Object> imageFailedPayload(String sessionId,
                                                   String model,
                                                   String requestedModel,
                                                   String prompt,
                                                   boolean img2img,
                                                   ReferenceImage referenceImage,
                                                   RuntimeException ex) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", safe(sessionId));
        payload.put("resolvedModel", safe(model));
        payload.put("requestedModel", safe(requestedModel));
        payload.put("mode", img2img ? "img2img" : "txt2img");
        payload.put("referenceName", referenceImage == null ? "" : safe(referenceImage.name()));
        payload.put("referenceMimeType", referenceImage == null ? "" : safe(referenceImage.mimeType()));
        payload.put("promptPreview", auditTrailService.preview(prompt));
        payload.put("errorType", ex == null ? "" : ex.getClass().getSimpleName());
        payload.put("errorMessage", ex == null ? "" : safe(ex.getMessage()));
        return payload;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
