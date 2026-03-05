package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.dto.ChatMediaInput;
import com.example.apiasistente.chat.dto.ChatResponse;
import com.example.apiasistente.chat.entity.ChatMessage;
import com.example.apiasistente.chat.service.ChatAuditTrailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Orquesta un turno completo de chat dentro de una transaccion.
 * Coordina preparacion de contexto, retrieval, generacion, persistencia y ensamblado de la respuesta HTTP.
 */
@Service
public class ChatTurnService {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnService.class);

    private final ChatTurnContextFactory contextFactory;
    private final ChatRagFlowService ragFlowService;
    private final ChatAssistantService assistantService;
    private final ChatHistoryService historyService;
    private final ChatSourceSnapshotService sourceSnapshotService;
    private final ChatSessionService sessionService;
    private final ChatRagDecisionEngine decisionEngine;
    private final ChatRagTelemetryService telemetryService;
    private final ChatAuditTrailService auditTrailService;

    public ChatTurnService(ChatTurnContextFactory contextFactory,
                           ChatRagFlowService ragFlowService,
                           ChatAssistantService assistantService,
                           ChatHistoryService historyService,
                           ChatSourceSnapshotService sourceSnapshotService,
                           ChatSessionService sessionService,
                           ChatRagDecisionEngine decisionEngine,
                           ChatRagTelemetryService telemetryService,
                           ChatAuditTrailService auditTrailService) {
        this.contextFactory = contextFactory;
        this.ragFlowService = ragFlowService;
        this.assistantService = assistantService;
        this.historyService = historyService;
        this.sourceSnapshotService = sourceSnapshotService;
        this.sessionService = sessionService;
        this.decisionEngine = decisionEngine;
        this.telemetryService = telemetryService;
        this.auditTrailService = auditTrailService;
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
            context = contextFactory.create(
                    username,
                    maybeSessionId,
                    userText,
                    requestedModel,
                    externalUserId,
                    media
            );
            log.info(
                    "chat_turn_stage stage=context_ready sessionId={} route={} ragNeeded={} reasoningLevel={} mediaCount={}",
                    context.session().getId(),
                    context.intentRoute(),
                    context.ragNeeded(),
                    context.turnPlan().reasoningLevel(),
                    context.preparedMedia().size()
            );
            auditTrailService.record("chat.turn.context_ready", turnContextPayload(context));

            // 2. Decide si el turno usa RAG y con que fuerza entra al contexto recuperado.
            stage = "rag";
            ChatRagContext ragContext = ragFlowService.resolve(context);
            log.info(
                    "chat_turn_stage stage=rag_resolved sessionId={} ragUsed={} missingEvidence={} route={} sourceCount={} contextTokens={}",
                    context.session().getId(),
                    ragContext.ragUsed(),
                    ragContext.missingEvidence(),
                    ragContext.ragRoute(),
                    ragContext.sources().size(),
                    ragContext.retrievalStats().contextTokens()
            );
            auditTrailService.record("chat.turn.rag_resolved", turnRagPayload(context, ragContext));

            // 3. Genera la respuesta final del asistente aplicando guardrails y retries si corresponden.
            stage = "assistant";
            ChatAssistantOutcome outcome = assistantService.answer(context, ragContext);
            ChatRagDecisionEngine.AnswerVerification answerVerification = ChatRagDecisionEngine.AnswerVerification.skip("post-check-not-needed");

            if (!ragContext.ragUsed() && !ragContext.missingEvidence()) {
                stage = "post-check";
                answerVerification = decisionEngine.verifyDirectAnswer(
                        context.userText(),
                        outcome.assistantText(),
                        context.turnPlan()
                );
                telemetryService.recordPostCheck(answerVerification, answerVerification.retryWithRag());
                logPostAnswerVerification(answerVerification, context);
                if (answerVerification.retryWithRag()) {
                    stage = "post-check-rag-retry";
                    ragContext = ragFlowService.resolveForced(context, answerVerification.reason());
                    outcome = assistantService.answer(context, ragContext);
                    log.info(
                            "chat_turn_stage stage=post_check_retry sessionId={} ragUsed={} route={} sourceCount={}",
                            context.session().getId(),
                            ragContext.ragUsed(),
                            ragContext.ragRoute(),
                            ragContext.sources().size()
                    );
                }
            }

            // 4. Persiste la salida del asistente y enlaza fuentes cuando hubo grounding real.
            stage = "persist";
            ChatMessage assistantMsg = historyService.saveAssistantMessage(context.session(), outcome.assistantText());
            if (ragContext.ragUsed()) {
                deferSourceSnapshotPersistence(context.session().getId(), assistantMsg.getId(), ragContext.scored());
            }

            // 5. Actualiza metadata operativa de la sesion y resume el resultado para el cliente.
            stage = "finalize";
            sessionService.touchSession(context.session());
            boolean safe = !ragContext.missingEvidence()
                    && (!ragContext.enforceGrounding() || outcome.answerAssessment().safe());
            double confidence = ragContext.missingEvidence()
                    ? 0.0
                    : (ragContext.ragUsed()
                    ? ragContext.groundingDecision().confidence()
                    : directAnswerConfidence(context.turnPlan().confidence(), answerVerification));
            int groundedSources = ragContext.ragUsed() ? Math.max(0, outcome.answerAssessment().groundedSources()) : 0;
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
            throw ex;
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

    private void logPostAnswerVerification(ChatRagDecisionEngine.AnswerVerification verification,
                                           ChatTurnContext context) {
        if (verification == null) {
            return;
        }
        log.info(
                "rag_post_check reviewed={} retry_with_rag={} confidence={} reason={} intent={} rag_mode={}",
                verification.reviewed(),
                verification.retryWithRag(),
                String.format(java.util.Locale.US, "%.3f", verification.confidence()),
                verification.reason(),
                context.intentRoute(),
                context.turnPlan().ragDecision().mode()
        );
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
}


