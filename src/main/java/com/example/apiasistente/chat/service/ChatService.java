package com.example.apiasistente.chat.service;

import com.example.apiasistente.chat.dto.ChatMediaInput;
import com.example.apiasistente.chat.dto.ChatMessageDto;
import com.example.apiasistente.chat.dto.ChatRagTelemetrySnapshotDto;
import com.example.apiasistente.chat.dto.ChatResponse;
import com.example.apiasistente.chat.dto.SessionDetailsDto;
import com.example.apiasistente.chat.dto.SessionSummaryDto;
import com.example.apiasistente.chat.entity.ChatMessage;
import com.example.apiasistente.chat.service.flow.ChatGeneratedImageStoreService;
import com.example.apiasistente.chat.service.flow.ChatHistoryService;
import com.example.apiasistente.chat.service.flow.ChatImageGenerationService;
import com.example.apiasistente.chat.service.flow.ChatRagTelemetryService;
import com.example.apiasistente.chat.service.flow.ChatSessionService;
import com.example.apiasistente.chat.service.flow.ChatTurnService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fachada del dominio de chat.
 * Expone operaciones simples para controllers y cola, y delega la orquestacion real
 * del turno a servicios especializados.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatTurnService turnService;
    private final ChatImageGenerationService imageGenerationService;
    private final ChatProcessRouter processRouter;
    private final ChatAuditTrailService auditTrailService;
    private final ChatSessionService sessionService;
    private final ChatHistoryService historyService;
    private final ChatRagTelemetryService ragTelemetryService;

    public ChatService(ChatTurnService turnService,
                       ChatImageGenerationService imageGenerationService,
                       ChatProcessRouter processRouter,
                       ChatAuditTrailService auditTrailService,
                       ChatSessionService sessionService,
                       ChatHistoryService historyService,
                       ChatRagTelemetryService ragTelemetryService) {
        this.turnService = turnService;
        this.imageGenerationService = imageGenerationService;
        this.processRouter = processRouter;
        this.auditTrailService = auditTrailService;
        this.sessionService = sessionService;
        this.historyService = historyService;
        this.ragTelemetryService = ragTelemetryService;
    }

    /**
     * Punto de entrada minimo para un turno sin seleccion explicita de modelo ni adjuntos.
     */
    public ChatResponse chat(String username, String maybeSessionId, String userText) {
        return chat(username, maybeSessionId, userText, null, null, List.of());
    }

    /**
     * Punto de entrada para un turno con modelo solicitado por el cliente.
     */
    public ChatResponse chat(String username, String maybeSessionId, String userText, String requestedModel) {
        return chat(username, maybeSessionId, userText, requestedModel, null, List.of());
    }

    /**
     * Punto de entrada para un turno con adjuntos pero sin aislamiento externo.
     */
    public ChatResponse chat(String username,
                             String maybeSessionId,
                             String userText,
                             String requestedModel,
                             List<ChatMediaInput> media) {
        return chat(username, maybeSessionId, userText, requestedModel, null, media);
    }

    /**
     * Punto de entrada para integraciones externas con aislamiento por usuario final.
     */
    public ChatResponse chat(String username,
                             String maybeSessionId,
                             String userText,
                             String requestedModel,
                             String externalUserId) {
        return chat(username, maybeSessionId, userText, requestedModel, externalUserId, List.of());
    }

    /**
     * Envia el turno completo al orquestador transaccional.
     * Esta es la sobrecarga que concentra todas las variantes anteriores.
     */
    public ChatResponse chat(String username,
                             String maybeSessionId,
                             String userText,
                             String requestedModel,
                             String externalUserId,
                             List<ChatMediaInput> media) {
        ChatProcessRouter.ProcessDecision processDecision = processRouter.decide(userText, requestedModel, media);
        String effectiveRequestedModel = resolveRequestedModelForExecution(requestedModel, processDecision);
        boolean hasImageMedia = hasImageMedia(media);
        boolean hasDocumentMedia = hasDocumentMedia(media);
        log.info(
                "chat_process_route sessionId={} requestedModel={} effectiveRequestedModel={} route={} pipeline={} source={} confidence={} usedLlm={} reason={} mediaCount={} promptPreview={}",
                safe(maybeSessionId),
                safe(requestedModel),
                safe(effectiveRequestedModel),
                processDecision.route(),
                processDecision.pipeline(),
                processDecision.source(),
                String.format(java.util.Locale.US, "%.3f", processDecision.confidence()),
                processDecision.usedLlm(),
                safe(processDecision.reason()),
                media == null ? 0 : media.size(),
                preview(userText)
        );
        auditTrailService.record("chat.process.route", routePayload(
                username,
                maybeSessionId,
                externalUserId,
                requestedModel,
                effectiveRequestedModel,
                processDecision,
                media == null ? 0 : media.size(),
                hasImageMedia,
                hasDocumentMedia,
                userText
        ));

        try {
            ChatResponse response;
            if (processDecision.route() == ChatProcessRouter.ProcessRoute.IMAGE_GENERATE) {
                response = imageGenerationService.generate(
                        username,
                        maybeSessionId,
                        userText,
                        effectiveRequestedModel,
                        externalUserId,
                        media
                );
            } else {
                response = turnService.chat(username, maybeSessionId, userText, effectiveRequestedModel, externalUserId, media);
            }
            auditTrailService.record("chat.process.done", donePayload(processDecision, response));
            return response;
        } catch (RuntimeException ex) {
            auditTrailService.record("chat.process.failed", failedPayload(
                    username,
                    maybeSessionId,
                    externalUserId,
                    requestedModel,
                    effectiveRequestedModel,
                    processDecision,
                    media == null ? 0 : media.size(),
                    hasImageMedia,
                    hasDocumentMedia,
                    userText,
                    ex
            ));
            throw ex;
        }
    }

    /**
     * Expone el historial serializado para consumo HTTP.
     */
    public List<ChatMessageDto> historyDto(String username, String sessionId) {
        return historyService.historyDto(username, sessionId);
    }

    /**
     * Devuelve entidades del historial para usos internos donde se necesita mas detalle.
     */
    public List<ChatMessage> historyEntitiesForInternalUse(String username, String sessionId) {
        return historyService.historyEntitiesForInternalUse(username, sessionId);
    }

    /**
     * Obtiene o crea la sesion generica activa del usuario.
     */
    public String activeSessionId(String username) {
        return sessionService.activeSessionId(username);
    }

    /**
     * Crea una sesion generica nueva para iniciar una conversacion separada.
     */
    public String newSession(String username) {
        return sessionService.newSession(username);
    }

    /**
     * Lista sesiones resumidas visibles para el usuario.
     */
    public List<SessionSummaryDto> listSessions(String username) {
        return sessionService.listSessions(username);
    }

    /**
     * Reactiva una sesion existente moviendola al tope de actividad.
     */
    public String activateSession(String username, String sessionId) {
        return sessionService.activateSession(username, sessionId);
    }

    /**
     * Actualiza el titulo manual de una sesion.
     */
    public void renameSession(String username, String sessionId, String title) {
        sessionService.renameSession(username, sessionId, title);
    }

    /**
     * Elimina una sesion especifica del usuario.
     */
    public void deleteSession(String username, String sessionId) {
        sessionService.deleteSession(username, sessionId);
    }

    /**
     * Elimina todas las sesiones genericas del usuario y devuelve cuantas borro.
     */
    public int deleteAllSessions(String username) {
        return sessionService.deleteAllSessions(username);
    }

    /**
     * Devuelve metadata detallada de una sesion concreta.
     */
    public SessionDetailsDto sessionDetails(String username, String sessionId) {
        return sessionService.sessionDetails(username, sessionId);
    }

    /**
     * Devuelve el snapshot operativo del motor de decisión RAG.
     */
    public ChatRagTelemetrySnapshotDto ragTelemetry() {
        return ragTelemetryService.snapshot();
    }

    public ChatGeneratedImageStoreService.StoredImage loadGeneratedImage(String username,
                                                                         String sessionId,
                                                                         String imageId) {
        return imageGenerationService.loadForUser(username, sessionId, imageId);
    }

    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 120).trim() + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean hasImageMedia(List<ChatMediaInput> media) {
        if (media == null || media.isEmpty()) {
            return false;
        }
        return media.stream().anyMatch(item -> item != null
                && item.getMimeType() != null
                && item.getMimeType().trim().toLowerCase().startsWith("image/"));
    }

    private boolean hasDocumentMedia(List<ChatMediaInput> media) {
        if (media == null || media.isEmpty()) {
            return false;
        }
        return media.stream().anyMatch(item -> item != null && (
                (item.getMimeType() != null && !item.getMimeType().isBlank()
                        && !item.getMimeType().trim().toLowerCase().startsWith("image/"))
                        || (item.getText() != null && !item.getText().isBlank())
        ));
    }

    private Map<String, Object> routePayload(String username,
                                             String maybeSessionId,
                                             String externalUserId,
                                             String requestedModel,
                                             String effectiveRequestedModel,
                                             ChatProcessRouter.ProcessDecision processDecision,
                                             int mediaCount,
                                             boolean hasImageMedia,
                                             boolean hasDocumentMedia,
                                             String userText) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", safe(username));
        payload.put("sessionId", safe(maybeSessionId));
        payload.put("externalUserId", safe(externalUserId));
        payload.put("requestedModel", safe(requestedModel));
        payload.put("effectiveRequestedModel", safe(effectiveRequestedModel));
        payload.put("route", processDecision.route().name());
        payload.put("pipeline", processDecision.pipeline().name());
        payload.put("recommendedModelAlias", safe(processDecision.recommendedModelAlias()));
        payload.put("needsRag", processDecision.needsRag());
        payload.put("needsAction", processDecision.needsAction());
        payload.put("expectedOutput", safe(processDecision.expectedOutput()));
        payload.put("routeSource", processDecision.source());
        payload.put("routeConfidence", String.format(java.util.Locale.US, "%.3f", processDecision.confidence()));
        payload.put("usedLlmRouter", processDecision.usedLlm());
        payload.put("routeReason", safe(processDecision.reason()));
        payload.put("mediaCount", mediaCount);
        payload.put("hasImageMedia", hasImageMedia);
        payload.put("hasDocumentMedia", hasDocumentMedia);
        payload.put("promptPreview", auditTrailService.preview(userText));
        return payload;
    }

    private Map<String, Object> donePayload(ChatProcessRouter.ProcessDecision processDecision, ChatResponse response) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("route", processDecision.route().name());
        payload.put("pipeline", processDecision.pipeline().name());
        payload.put("needsRag", processDecision.needsRag());
        payload.put("needsAction", processDecision.needsAction());
        payload.put("expectedOutput", safe(processDecision.expectedOutput()));
        payload.put("routeSource", processDecision.source());
        payload.put("routeConfidence", String.format(java.util.Locale.US, "%.3f", processDecision.confidence()));
        payload.put("resolvedSessionId", response == null ? "" : safe(response.getSessionId()));
        payload.put("ragUsed", response != null && response.isRagUsed());
        payload.put("ragNeeded", response != null && response.isRagNeeded());
        payload.put("reasoningLevel", response == null ? "" : safe(response.getReasoningLevel()));
        return payload;
    }

    private Map<String, Object> failedPayload(String username,
                                              String maybeSessionId,
                                              String externalUserId,
                                              String requestedModel,
                                              String effectiveRequestedModel,
                                              ChatProcessRouter.ProcessDecision processDecision,
                                              int mediaCount,
                                              boolean hasImageMedia,
                                              boolean hasDocumentMedia,
                                              String userText,
                                              RuntimeException ex) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", safe(username));
        payload.put("sessionId", safe(maybeSessionId));
        payload.put("externalUserId", safe(externalUserId));
        payload.put("requestedModel", safe(requestedModel));
        payload.put("effectiveRequestedModel", safe(effectiveRequestedModel));
        payload.put("route", processDecision.route().name());
        payload.put("pipeline", processDecision.pipeline().name());
        payload.put("recommendedModelAlias", safe(processDecision.recommendedModelAlias()));
        payload.put("needsRag", processDecision.needsRag());
        payload.put("needsAction", processDecision.needsAction());
        payload.put("expectedOutput", safe(processDecision.expectedOutput()));
        payload.put("routeSource", processDecision.source());
        payload.put("routeConfidence", String.format(java.util.Locale.US, "%.3f", processDecision.confidence()));
        payload.put("usedLlmRouter", processDecision.usedLlm());
        payload.put("mediaCount", mediaCount);
        payload.put("hasImageMedia", hasImageMedia);
        payload.put("hasDocumentMedia", hasDocumentMedia);
        payload.put("promptPreview", auditTrailService.preview(userText));
        payload.put("errorType", ex == null ? "" : ex.getClass().getSimpleName());
        payload.put("errorMessage", ex == null ? "" : safe(ex.getMessage()));
        return payload;
    }

    /**
     * En modo auto, reemplaza el modelo solicitado por el alias recomendado por el router.
     * En modo manual respeta exactamente el modelo enviado por el cliente.
     */
    private String resolveRequestedModelForExecution(String requestedModel,
                                                     ChatProcessRouter.ProcessDecision processDecision) {
        if (!isAutoRequest(requestedModel)) {
            return requestedModel;
        }
        if (processDecision == null || processDecision.recommendedModelAlias() == null
                || processDecision.recommendedModelAlias().isBlank()) {
            return requestedModel;
        }
        return processDecision.recommendedModelAlias();
    }

    private boolean isAutoRequest(String requestedModel) {
        return requestedModel == null
                || requestedModel.isBlank()
                || ChatModelSelector.AUTO_ALIAS.equalsIgnoreCase(requestedModel)
                || ChatModelSelector.DEFAULT_ALIAS.equalsIgnoreCase(requestedModel);
    }
}


