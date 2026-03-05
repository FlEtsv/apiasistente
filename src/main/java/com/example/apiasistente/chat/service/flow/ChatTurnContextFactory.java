package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.dto.ChatMediaInput;
import com.example.apiasistente.auth.entity.AppUser;
import com.example.apiasistente.chat.entity.ChatMessage;
import com.example.apiasistente.chat.entity.ChatSession;
import com.example.apiasistente.chat.service.ChatPromptSignals;
import com.example.apiasistente.chat.service.ChatTurnPlanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Construye el contexto inmutable de un turno antes de invocar RAG o modelo.
 * Aqui se resuelven sesion, mensaje persistido, adjuntos preparados y señales heuristicas del turno.
 */
@Service
public class ChatTurnContextFactory {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnContextFactory.class);

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

        ChatMessage userMsg = historyService.saveUserMessage(session, userText);

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
        boolean ragNeeded = turnPlan.ragNeeded();
        boolean complexQuery = turnPlan.complexQuery();
        boolean multiStepQuery = turnPlan.multiStepQuery();
        boolean followUpCorrectionMode = ChatPromptSignals.isFormatRevision(userText);
        boolean textRenderMode = intentRoute == ChatPromptSignals.IntentRoute.TEXT_RENDER;
        boolean directExecutionMode = textRenderMode || followUpCorrectionMode;
        boolean taskCompletionMode = directExecutionMode
                || intentRoute == ChatPromptSignals.IntentRoute.TASK_SIMPLE
                || complexQuery
                || multiStepQuery;

        // Deja trazabilidad para entender por que el pipeline eligio cierto camino.
        log.info(
                "rag_decision={} rag_mode={} rag_reason=\"{}\" rag_signals={} intent={} directExecution={} taskCompletion={} textRender={} reasoningLevel={} complex={} multiStep={} confidence={}",
                ragDecision.enabled() ? "ON" : "OFF",
                ragDecision.mode(),
                ragDecision.reason(),
                ragDecision.signals(),
                intentRoute,
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
                    "Turn planner intent={} ragNeeded={} directExecution={} taskCompletion={} textRender={} reasoningLevel={} complex={} multiStep={} confidence={} ragMode={} ragReason=\"{}\"",
                    intentRoute,
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
        log.info(
                "chat_turn_matrix hasImageMedia={} hasDocumentMedia={} intent={} ragMode={} reasoningLevel={} expectedPath={}",
                hasImageMedia,
                hasDocumentMedia,
                intentRoute,
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
                ragNeeded,
                complexQuery,
                multiStepQuery,
                textRenderMode,
                directExecutionMode,
                taskCompletionMode
        );
    }
}


