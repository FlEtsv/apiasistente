package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.dto.ChatMediaInput;
import com.example.apiasistente.chat.dto.ChatResponse;
import com.example.apiasistente.chat.entity.ChatMessage;
import com.example.apiasistente.chat.service.ChatAuditTrailService;
import com.example.apiasistente.chat.service.ChatRuntimeAdaptationService;
import com.example.apiasistente.chat.service.RouterFeedbackStore;
import com.example.apiasistente.monitoring.service.AppMetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Orquesta un turno completo de chat dentro de una transaccion.
 * Coordina preparacion de contexto, retrieval, generacion, persistencia y ensamblado de la respuesta HTTP.
 */
@Service
public class ChatTurnService {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatTurnContextFactory contextFactory;
    private final ChatRagFlowService ragFlowService;
    private final ChatAssistantService assistantService;
    private final ChatHistoryService historyService;
    private final ChatSourceSnapshotService sourceSnapshotService;
    private final ChatSessionService sessionService;
    private final ChatRagPostCheckFlowService postCheckFlowService;
    private final ChatRagTelemetryService telemetryService;
    private final ChatAuditTrailService auditTrailService;
    private ChatRuntimeAdaptationService runtimeAdaptationService;
    private RouterFeedbackStore feedbackStore;
    private AppMetricsService metricsService;

    public ChatTurnService(ChatTurnContextFactory contextFactory,
                           ChatRagFlowService ragFlowService,
                           ChatAssistantService assistantService,
                           ChatHistoryService historyService,
                           ChatSourceSnapshotService sourceSnapshotService,
                           ChatSessionService sessionService,
                           ChatRagPostCheckFlowService postCheckFlowService,
                           ChatRagTelemetryService telemetryService,
                           ChatAuditTrailService auditTrailService) {
        this.contextFactory = contextFactory;
        this.ragFlowService = ragFlowService;
        this.assistantService = assistantService;
        this.historyService = historyService;
        this.sourceSnapshotService = sourceSnapshotService;
        this.sessionService = sessionService;
        this.postCheckFlowService = postCheckFlowService;
        this.telemetryService = telemetryService;
        this.auditTrailService = auditTrailService;
    }

    @Autowired(required = false)
    void setRuntimeAdaptationService(ChatRuntimeAdaptationService runtimeAdaptationService) {
        this.runtimeAdaptationService = runtimeAdaptationService;
    }

    @Autowired(required = false)
    void setFeedbackStore(RouterFeedbackStore feedbackStore) {
        this.feedbackStore = feedbackStore;
    }

    @Autowired(required = false)
    void setMetricsService(AppMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    /**
     * Ejecuta el pipeline completo de un turno.
     */
    @Transactional
    public ChatResponse chat(String username,
                             String maybeSessionId,
                             String userText,
                             String requestedModel,
                             String externalUserId,
                             List<ChatMediaInput> media) {
        long turnStartNanos = System.nanoTime();
        Map<String, Long> stageTimes = new LinkedHashMap<>();
        String stage = "context";
        ChatTurnContext context = null;
        try {
            log.info(
                    "chat_turn_start sessionId={} externalUserId={} requestedModel={} mediaCount={} messagePreview={}",
                    safe(maybeSessionId),
                    safe(externalUserId),
                    safe(requestedModel),
                    media == null ? 0 : media.size(),
                    preview(userText)
            );
            auditTrailService.record("chat.turn.start", turnStartPayload(
                    maybeSessionId,
                    externalUserId,
                    requestedModel,
                    media == null ? 0 : media.size(),
                    userText
            ));

            // 1. Fija sesion, historial, adjuntos y plan heuristico del turno.
            long t0 = System.nanoTime();
            context = contextFactory.create(
                    username,
                    maybeSessionId,
                    userText,
                    requestedModel,
                    externalUserId,
                    media
            );
            stageTimes.put("context", elapsedMillis(t0));
            log.info(
                    "chat_turn_stage stage=context_ready sessionId={} route={} ragNeeded={} reasoningLevel={} mediaCount={} ms={}",
                    context.session().getId(),
                    context.intentRoute(),
                    context.ragNeeded(),
                    context.turnPlan().reasoningLevel(),
                    context.preparedMedia().size(),
                    stageTimes.get("context")
            );
            auditTrailService.record("chat.turn.context_ready", turnContextPayload(context));

            // 2. Decide si el turno usa RAG y con que fuerza entra al contexto recuperado.
            stage = "rag";
            long t1 = System.nanoTime();
            ChatRagContext ragContext = ragFlowService.resolve(context);
            stageTimes.put("rag", elapsedMillis(t1));
            log.info(
                    "chat_turn_stage stage=rag_resolved sessionId={} ragUsed={} missingEvidence={} route={} sourceCount={} contextTokens={} ms={}",
                    context.session().getId(),
                    ragContext.ragUsed(),
                    ragContext.missingEvidence(),
                    ragContext.ragRoute(),
                    ragContext.sources().size(),
                    ragContext.retrievalStats().contextTokens(),
                    stageTimes.get("rag")
            );
            auditTrailService.record("chat.turn.rag_resolved", turnRagPayload(context, ragContext));

            // 3. Genera la respuesta final del asistente aplicando guardrails y retries si corresponden.
            stage = "assistant";
            long t2 = System.nanoTime();
            ChatAssistantOutcome outcome = assistantService.answer(context, ragContext);
            stageTimes.put("assistant", elapsedMillis(t2));
            stage = "post-check";
            long t3 = System.nanoTime();
            ChatRagPostCheckFlowService.PostCheckResult postCheck = postCheckFlowService.run(context, ragContext, outcome);
            stageTimes.put("post-check", elapsedMillis(t3));
            ragContext = postCheck.ragContext();
            outcome = postCheck.outcome();
            ChatRagDecisionEngine.AnswerVerification answerVerification = postCheck.answerVerification();

            // Si el post-check forzó un reintento con RAG, significa que la ruta inicial fue subóptima.
            if (answerVerification.reviewed() && answerVerification.retryWithRag()) {
                recordRouteFeedback(userText, "CHAT", "RAG", "post-check-rag-retry");
            }

            // 4. Persiste la salida del asistente con metadata de ejecucion para trazabilidad del historial.
            stage = "persist";
            String assistantMetadata = buildAssistantMetadata(context, ragContext, stageTimes);
            ChatMessage assistantMsg = historyService.saveAssistantMessage(
                    context.session(), outcome.assistantText(), assistantMetadata);
            if (ragContext.ragUsed()) {
                deferSourceSnapshotPersistence(context.session().getId(), assistantMsg.getId(), ragContext.scored());
            }

            // 5. Actualiza metadata operativa de la sesion y resume el resultado para el cliente.
            stage = "finalize";
            sessionService.touchSession(context.session());
            boolean terminalFallback = isTerminalFallbackResponse(outcome.assistantText(), ragContext.fallbackMessage());
            boolean safe = !ragContext.missingEvidence()
                    && !terminalFallback
                    && (!ragContext.enforceGrounding() || outcome.answerAssessment().safe());
            double confidence = (ragContext.missingEvidence() || terminalFallback)
                    ? 0.0
                    : (ragContext.ragUsed()
                    ? ragContext.groundingDecision().confidence()
                    : directAnswerConfidence(context.turnPlan().confidence(), answerVerification));
            int groundedSources = ragContext.ragUsed() && !terminalFallback
                    ? Math.max(0, outcome.answerAssessment().groundedSources())
                    : 0;
            boolean ragNeeded = context.ragNeeded() || answerVerification.retryWithRag();
            telemetryService.recordTurnResult(
                    ragContext.ragUsed(),
                    ragNeeded,
                    confidence,
                    context.turnPlan().reasoningLevel()
            );
            ChatResponse response = new ChatResponse(
                    context.session().getId(),
                    outcome.assistantText(),
                    ragContext.sources(),
                    safe,
                    confidence,
                    groundedSources,
                    ragContext.ragUsed(),
                    ragNeeded,
                    context.turnPlan().reasoningLevel().name()
            );
            stageTimes.put("total", elapsedMillis(turnStartNanos));
            log.info(
                    "chat_turn_trace sessionId={} stages={} mediaCount={} ragUsed={} postCheckRetry={}",
                    context.session().getId(),
                    stageTimes,
                    context.preparedMedia().size(),
                    ragContext.ragUsed(),
                    answerVerification.reviewed() && answerVerification.retryWithRag()
            );
            log.info(
                    "chat_turn_done sessionId={} ragUsed={} ragNeeded={} safe={} groundedSources={} confidence={} replyPreview={}",
                    response.getSessionId(),
                    response.isRagUsed(),
                    response.isRagNeeded(),
                    response.isSafe(),
                    response.getGroundedSources(),
                    String.format(java.util.Locale.US, "%.3f", response.getConfidence()),
                    preview(response.getReply())
            );
            auditTrailService.record("chat.turn.done", turnDonePayload(response));
            if (metricsService != null) {
                metricsService.recordChatTurnSuccess(response, elapsedMillis(turnStartNanos));
            }
            return response;
        } catch (RuntimeException ex) {
            log.error(
                    "chat_turn_failed stage={} requestedSessionId={} resolvedSessionId={} externalUserId={} requestedModel={} mediaCount={} messagePreview={} causeType={} cause={}",
                    stage,
                    safe(maybeSessionId),
                    context == null ? "" : safe(context.session().getId()),
                    safe(externalUserId),
                    safe(requestedModel),
                    media == null ? 0 : media.size(),
                    preview(userText),
                    ex.getClass().getSimpleName(),
                    safe(ex.getMessage()),
                    ex
            );
            auditTrailService.record("chat.turn.failed", turnFailedPayload(
                    stage,
                    maybeSessionId,
                    externalUserId,
                    requestedModel,
                    media == null ? 0 : media.size(),
                    userText,
                    ex
            ));
            if (metricsService != null) {
                metricsService.recordChatTurnFailure(
                        stage,
                        ex.getClass().getSimpleName(),
                        elapsedMillis(turnStartNanos)
                );
            }
            // Si el fallo ocurrio en RAG, generacion o post-check, el contexto de sesion ya esta
            // establecido y el mensaje del usuario ya fue guardado. En lugar de propagar la excepcion
            // (que haria rollback y perderia el mensaje del usuario), guardamos un mensaje de error
            // amigable y devolvemos una respuesta degradada al cliente.
            if (context != null && isRecoverableStage(stage)) {
                try {
                    String fallback = buildLlmErrorFallback(stage, ex);
                    historyService.saveAssistantMessage(context.session(), fallback, null);
                    sessionService.touchSession(context.session());
                    log.info("chat_turn_degraded_response stage={} sessionId={}", stage, context.session().getId());
                    return new ChatResponse(
                            context.session().getId(),
                            fallback,
                            List.of(),
                            false,
                            0.0,
                            0,
                            false,
                            context.ragNeeded(),
                            context.turnPlan().reasoningLevel().name()
                    );
                } catch (RuntimeException fallbackEx) {
                    log.warn("chat_turn_fallback_save_failed sessionId={} cause={}",
                            context.session().getId(), fallbackEx.getMessage());
                }
            }
            throw ex;
        } finally {
            recordTurnLatency(turnStartNanos);
        }
    }

    private double directAnswerConfidence(double heuristicConfidence,
                                          ChatRagDecisionEngine.AnswerVerification answerVerification) {
        if (answerVerification == null || !answerVerification.reviewed()) {
            return heuristicConfidence;
        }
        if (answerVerification.confidence() <= 0.0) {
            return heuristicConfidence;
        }
        return Math.min(heuristicConfidence, answerVerification.confidence());
    }

    private void deferSourceSnapshotPersistence(String sessionId,
                                                Long assistantMessageId,
                                                List<com.example.apiasistente.rag.service.RagService.ScoredChunk> scored) {
        List<ChatSourceSnapshotService.SourceSnapshot> snapshots = sourceSnapshotService.toSnapshots(scored);
        if (assistantMessageId == null || snapshots.isEmpty()) {
            return;
        }

        log.info(
                "chat_turn_stage stage=sources_deferred sessionId={} messageId={} sourceCount={}",
                safe(sessionId),
                assistantMessageId,
                snapshots.size()
        );

        Runnable task = () -> {
            try {
                sourceSnapshotService.persistAfterCommit(assistantMessageId, sessionId, snapshots);
            } catch (RuntimeException ex) {
                log.warn(
                        "No se pudieron persistir snapshots de fuentes RAG para sessionId={} messageId={} cause={}",
                        safe(sessionId),
                        assistantMessageId,
                        safe(ex.getMessage()),
                        ex
                );
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }

        task.run();
    }

    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 140) {
            return normalized;
        }
        return normalized.substring(0, 140).trim() + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean isTerminalFallbackResponse(String assistantText, String fallbackMessage) {
        String normalizedReply = normalizeForFallbackComparison(assistantText);
        String normalizedFallback = normalizeForFallbackComparison(fallbackMessage);
        return !normalizedReply.isEmpty()
                && !normalizedFallback.isEmpty()
                && normalizedReply.startsWith(normalizedFallback);
    }

    private String normalizeForFallbackComparison(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private Map<String, Object> turnStartPayload(String maybeSessionId,
                                                 String externalUserId,
                                                 String requestedModel,
                                                 int mediaCount,
                                                 String userText) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestedSessionId", safe(maybeSessionId));
        payload.put("externalUserId", safe(externalUserId));
        payload.put("requestedModel", safe(requestedModel));
        payload.put("mediaCount", mediaCount);
        payload.put("messagePreview", auditTrailService.preview(userText));
        return payload;
    }

    private Map<String, Object> turnContextPayload(ChatTurnContext context) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", safe(context.session().getId()));
        payload.put("intentRoute", context.intentRoute().name());
        payload.put("ragNeeded", context.ragNeeded());
        payload.put("reasoningLevel", context.turnPlan().reasoningLevel().name());
        payload.put("mediaCount", context.preparedMedia().size());
        return payload;
    }

    private Map<String, Object> turnRagPayload(ChatTurnContext context, ChatRagContext ragContext) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", safe(context.session().getId()));
        payload.put("ragUsed", ragContext.ragUsed());
        payload.put("ragRoute", ragContext.ragRoute().name());
        payload.put("missingEvidence", ragContext.missingEvidence());
        payload.put("sourceCount", ragContext.sources().size());
        payload.put("contextTokens", ragContext.retrievalStats().contextTokens());
        return payload;
    }

    private Map<String, Object> turnDonePayload(ChatResponse response) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", response == null ? "" : safe(response.getSessionId()));
        payload.put("ragUsed", response != null && response.isRagUsed());
        payload.put("ragNeeded", response != null && response.isRagNeeded());
        payload.put("safe", response != null && response.isSafe());
        payload.put("groundedSources", response == null ? 0 : response.getGroundedSources());
        payload.put("confidence", response == null ? 0.0 : response.getConfidence());
        payload.put("reasoningLevel", response == null ? "" : safe(response.getReasoningLevel()));
        payload.put("replyPreview", response == null ? "" : auditTrailService.preview(response.getReply()));
        return payload;
    }

    private Map<String, Object> turnFailedPayload(String stage,
                                                  String maybeSessionId,
                                                  String externalUserId,
                                                  String requestedModel,
                                                  int mediaCount,
                                                  String userText,
                                                  RuntimeException ex) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", safe(stage));
        payload.put("requestedSessionId", safe(maybeSessionId));
        payload.put("externalUserId", safe(externalUserId));
        payload.put("requestedModel", safe(requestedModel));
        payload.put("mediaCount", mediaCount);
        payload.put("messagePreview", auditTrailService.preview(userText));
        payload.put("errorType", ex == null ? "" : ex.getClass().getSimpleName());
        payload.put("errorMessage", ex == null ? "" : safe(ex.getMessage()));
        return payload;
    }

    /**
     * Registra feedback al RouterFeedbackStore cuando se detecta que la ruta inicial fue suboptima.
     */
    private void recordRouteFeedback(String userText,
                                     String decidedRoute,
                                     String correctRoute,
                                     String reason) {
        if (feedbackStore == null) {
            return;
        }
        try {
            feedbackStore.recordCorrection(userText, decidedRoute, correctRoute, reason);
        } catch (Exception ex) {
            log.debug("router_feedback_record_failed cause={}", ex.getMessage());
        }
    }

    /**
     * Construye el JSON de metadata para el mensaje del asistente.
     * Incluye modelo usado, ruta, pipeline, timing de etapas y uso de RAG.
     */
    private String buildAssistantMetadata(ChatTurnContext context,
                                          ChatRagContext ragContext,
                                          Map<String, Long> stageTimes) {
        try {
            LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
            meta.put("requestedModel", safe(context.requestedModel()));
            meta.put("intentRoute", context.intentRoute().name());
            meta.put("ragUsed", ragContext.ragUsed());
            meta.put("ragRoute", ragContext.ragRoute().name());
            meta.put("sourceCount", ragContext.sources().size());
            meta.put("reasoningLevel", context.turnPlan().reasoningLevel().name());
            meta.put("hasImageMedia", context.preparedMedia().stream()
                    .anyMatch(m -> m != null && m.imageBase64() != null && !m.imageBase64().isBlank()));
            meta.put("hasDocumentMedia", context.preparedMedia().stream()
                    .anyMatch(m -> m != null && m.documentText() != null && !m.documentText().isBlank()));
            meta.put("stageTimes", stageTimes);
            return MAPPER.writeValueAsString(meta);
        } catch (Exception ex) {
            return null;
        }
    }

    private void recordTurnLatency(long turnStartNanos) {
        if (runtimeAdaptationService == null) {
            return;
        }
        long elapsedMs = elapsedMillis(turnStartNanos);
        runtimeAdaptationService.recordTurnLatency(elapsedMs);
    }

    private long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    /**
     * Etapas desde las que se puede recuperar devolviendo un mensaje degradado al usuario.
     * El contexto ya esta establecido y el mensaje del usuario ya fue guardado.
     */
    private boolean isRecoverableStage(String stage) {
        return "rag".equals(stage) || "assistant".equals(stage) || "post-check".equals(stage);
    }

    /**
     * Construye el mensaje de error amigable segun el tipo de fallo.
     */
    private String buildLlmErrorFallback(String stage, RuntimeException ex) {
        String cause = ex.getMessage();
        boolean isTimeout = cause != null && (cause.contains("timed out") || cause.contains("timeout") || cause.contains("Read timed out"));
        boolean isUnavailable = cause != null && (cause.contains("Connection refused") || cause.contains("no disponible") || cause.contains("not available"));
        if (isTimeout) {
            return "El modelo tardó demasiado en responder. Prueba con una pregunta más corta o espera un momento.";
        }
        if (isUnavailable) {
            return "El modelo de IA no está disponible en este momento. Verifica que Ollama esté activo e intenta de nuevo.";
        }
        return "No pude procesar tu consulta" + ("rag".equals(stage) ? " (error en retrieval)" : "") + ". Intenta de nuevo.";
    }
}

