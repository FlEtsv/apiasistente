package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.auth.entity.AppUser;
import com.example.apiasistente.chat.config.ChatImageGenerationProperties;
import com.example.apiasistente.chat.dto.ChatResponse;
import com.example.apiasistente.chat.entity.ChatSession;
import com.example.apiasistente.chat.service.ChatModelSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Flujo dedicado para generación de imágenes desde el chat.
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

    public ChatImageGenerationService(ChatImageGenerationProperties properties,
                                      ChatModelSelector modelSelector,
                                      ChatSessionService sessionService,
                                      ChatHistoryService historyService,
                                      ChatImageGeneratorClient imageGeneratorClient,
                                      ChatGeneratedImageStoreService imageStoreService) {
        this.properties = properties;
        this.modelSelector = modelSelector;
        this.sessionService = sessionService;
        this.historyService = historyService;
        this.imageGeneratorClient = imageGeneratorClient;
        this.imageStoreService = imageStoreService;
    }

    @Transactional
    public ChatResponse generate(String username,
                                 String maybeSessionId,
                                 String userText,
                                 String requestedModel,
                                 String externalUserId) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("La generación de imagen está desactivada.");
        }

        String prompt = normalizePrompt(userText);
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("El prompt de imagen no puede estar vacío.");
        }

        String normalizedExternalUserId = sessionService.normalizeExternalUserId(externalUserId);
        AppUser user = sessionService.requireUser(username);
        ChatSession session = sessionService.resolveSession(user, maybeSessionId, normalizedExternalUserId);
        sessionService.touchSession(session);
        sessionService.autoTitleIfDefault(session, prompt);
        historyService.saveUserMessage(session, prompt);

        String model = modelSelector.resolveImageModel(requestedModel);
        log.info(
                "chat_image_generation_start sessionId={} model={} promptPreview={}",
                session.getId(),
                model,
                preview(prompt)
        );

        ChatImageGeneratorClient.GeneratedImage generated = imageGeneratorClient.generate(prompt, model);
        String imageId = imageStoreService.store(session.getId(), generated.mimeType(), generated.bytes());
        String imageUrl = "/api/chat/sessions/%s/images/%s".formatted(session.getId(), imageId);

        String reply = """
                Imagen generada con modelo `%s`.

                ![Imagen generada](%s)

                Prompt: %s
                """.formatted(model, imageUrl, prompt);

        historyService.saveAssistantMessage(session, reply);
        sessionService.touchSession(session);
        log.info(
                "chat_image_generation_done sessionId={} model={} imageId={} mimeType={}",
                session.getId(),
                model,
                imageId,
                generated.mimeType()
        );

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
    }

    @Transactional(readOnly = true)
    public ChatGeneratedImageStoreService.StoredImage loadForUser(String username,
                                                                  String sessionId,
                                                                  String imageId) {
        AppUser user = sessionService.requireUser(username);
        sessionService.requireOwnedGenericSession(user, sessionId);
        return imageStoreService.load(sessionId, imageId);
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
}
