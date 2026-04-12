package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.dto.ChatMediaInput;
import com.example.apiasistente.auth.entity.AppUser;
import com.example.apiasistente.chat.entity.ChatMessage;
import com.example.apiasistente.chat.entity.ChatSession;
import com.example.apiasistente.chat.service.ChatPromptSignals;
import com.example.apiasistente.chat.service.ChatTurnPlanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Construye el contexto inmutable de un turno antes de invocar RAG o modelo.
 * Aqui se resuelven sesion, mensaje persistido, adjuntos preparados y señales heuristicas del turno.
 */
@Service
public class ChatTurnContextFactory {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnContextFactory.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatSessionService sessionService;
    private final ChatHistoryService historyService;
    private final ChatMediaService mediaService;
    private final ChatTurnPlanner turnPlanner;

    public ChatTurnContextFactory(ChatSessionService sessionService,
                                  ChatHistoryService historyService,
                                  ChatMediaService mediaService,
                                  ChatTurnPlanner turnPlanner) {
        this.sessionService = sessionService;
        this.historyService = historyService;
        this.mediaService = mediaService;
        this.turnPlanner = turnPlanner;
    }

    /**
     * Prepara todos los insumos necesarios para el resto del pipeline.
     */
    public ChatTurnContext create(String username,
                                  String maybeSessionId,
                                  String userText,
                                  String requestedModel,
                                  String externalUserId,
                                  List<ChatMediaInput> media) {
        // Normaliza identificadores y adjuntos antes de tocar la sesion para que el resto del flujo trabaje con datos estables.
        String normalizedExternalUserId = sessionService.normalizeExternalUserId(externalUserId);
        List<ChatMediaService.PreparedMedia> preparedMedia = mediaService.prepareMedia(media);

        // Resuelve la sesion efectiva del turno y persiste el mensaje del usuario como base del historial.
        AppUser user = sessionService.requireUser(username);
        ChatSession session = sessionService.resolveSession(user, maybeSessionId, normalizedExternalUserId);
        sessionService.touchSession(session);
        sessionService.autoTitleIfDefault(session, userText);

        String userMediaMetadata = buildUserMediaMetadata(preparedMedia);
        ChatMessage userMsg = historyService.saveUserMessage(session, userText, userMediaMetadata);

        // Deriva las señales que gobiernan modelo, uso de RAG y modo de ejecucion de la respuesta.
        boolean hasImageMedia = mediaService.hasImageMedia(preparedMedia);
        boolean hasDocumentMedia = mediaService.hasDocumentMedia(preparedMedia);
        ChatTurnPlanner.TurnPlan turnPlan = turnPlanner.plan(
                userText,
                !preparedMedia.isEmpty(),
                hasImageMedia,
                hasDocumentMedia
        );

        ChatPromptSignals.IntentRoute intentRoute = turnPlan.intentRoute();
        ChatPromptSignals.RagDecision ragDecision = turnPlan.ragDecision();
        ChatPromptSignals.IntentProfile intentProfile = ChatPromptSignals.captureIntent(
                userText,
                hasDocumentMedia,
                hasImageMedia
        );
        boolean ragNeeded = turnPlan.ragNeeded();
        boolean complexQuery = turnPlan.complexQuery();
        boolean multiStepQuery = turnPlan.multiStepQuery();
        boolean followUpCorrectionMode = ChatPromptSignals.isFormatRevision(userText);
        boolean textRenderMode = intentRoute == ChatPromptSignals.IntentRoute.TEXT_RENDER;
        boolean directExecutionMode = textRenderMode || followUpCorrectionMode;
        boolean taskCompletionMode = (directExecutionMode
                || intentRoute == ChatPromptSignals.IntentRoute.TASK_SIMPLE
                || complexQuery
                || multiStepQuery)
                && !intentProfile.requiresConfirmation();

        // Deja trazabilidad para entender por que el pipeline eligio cierto camino.
        log.info(
                "rag_decision={} rag_mode={} rag_reason=\"{}\" rag_signals={} intent={} intentCategory={} responseStyle={} homeAutomation={} autonomy={} requiresConfirmation={} directExecution={} taskCompletion={} textRender={} reasoningLevel={} complex={} multiStep={} confidence={}",
                ragDecision.enabled() ? "ON" : "OFF",
                ragDecision.mode(),
                ragDecision.reason(),
                ragDecision.signals(),
                intentRoute,
                intentProfile.category(),
                intentProfile.responseStyle(),
                intentProfile.homeAutomation(),
                intentProfile.autonomousDecisionRequested(),
                intentProfile.requiresConfirmation(),
                directExecutionMode,
                taskCompletionMode,
                textRenderMode,
                turnPlan.reasoningLevel(),
                complexQuery,
                multiStepQuery,
                String.format(Locale.US, "%.3f", turnPlan.confidence())
        );

        if (log.isDebugEnabled()) {
            log.debug(
                    "Turn planner intent={} intentCategory={} responseStyle={} homeAutomation={} autonomy={} requiresConfirmation={} ragNeeded={} directExecution={} taskCompletion={} textRender={} reasoningLevel={} complex={} multiStep={} confidence={} ragMode={} ragReason=\"{}\"",
                    intentRoute,
                    intentProfile.category(),
                    intentProfile.responseStyle(),
                    intentProfile.homeAutomation(),
                    intentProfile.autonomousDecisionRequested(),
                    intentProfile.requiresConfirmation(),
                    ragNeeded,
                    directExecutionMode,
                    taskCompletionMode,
                    textRenderMode,
                    turnPlan.reasoningLevel(),
                    complexQuery,
                    multiStepQuery,
                    String.format(Locale.US, "%.3f", turnPlan.confidence()),
                    ragDecision.mode(),
                    ragDecision.reason()
            );
        }
        log.info("chat_turn_matrix hasImageMedia={} hasDocumentMedia={} intent={} intentCategory={} responseStyle={} ragMode={} reasoningLevel={} expectedPath={}",
                hasImageMedia,
                hasDocumentMedia,
                intentRoute,
                intentProfile.category(),
                intentProfile.responseStyle(),
                ragDecision.mode(),
                turnPlan.reasoningLevel(),
                hasImageMedia ? "visual-detection+chat" : "text-chat"
        );

        return new ChatTurnContext(
                username,
                userText,
                requestedModel,
                normalizedExternalUserId,
                session,
                userMsg,
                preparedMedia,
                turnPlan,
                intentRoute,
                intentProfile,
                ragNeeded,
                complexQuery,
                multiStepQuery,
                textRenderMode,
                directExecutionMode,
                taskCompletionMode
        );
    }

    /**
     * Construye JSON de metadata para el mensaje del usuario con referencias de media adjunta.
     * Permite que el historial muestre que habia imagenes o documentos en el turno.
     */
    private String buildUserMediaMetadata(List<ChatMediaService.PreparedMedia> preparedMedia) {
        if (preparedMedia == null || preparedMedia.isEmpty()) {
            return null;
        }

        boolean hasImages = false;
        boolean hasDocs = false;
        List<String> imageNames = new ArrayList<>();
        List<String> docNames = new ArrayList<>();

        for (ChatMediaService.PreparedMedia m : preparedMedia) {
            if (m == null) continue;
            boolean isImage = m.imageBase64() != null && !m.imageBase64().isBlank();
            boolean isDoc = m.documentText() != null && !m.documentText().isBlank();
            if (isImage) {
                hasImages = true;
                if (m.name() != null && !m.name().isBlank()) imageNames.add(m.name());
            } else if (isDoc) {
                hasDocs = true;
                if (m.name() != null && !m.name().isBlank()) docNames.add(m.name());
            }
        }

        if (!hasImages && !hasDocs) {
            return null;
        }

        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("mediaCount", preparedMedia.size());
            meta.put("hasImages", hasImages);
            meta.put("hasDocuments", hasDocs);
            if (!imageNames.isEmpty()) meta.put("imageNames", imageNames);
            if (!docNames.isEmpty()) meta.put("documentNames", docNames);
            return MAPPER.writeValueAsString(meta);
        } catch (Exception ex) {
            return null;
        }
    }
}


