package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.dto.ChatMediaInput;
import com.example.apiasistente.chat.dto.ChatResponse;
import com.example.apiasistente.chat.entity.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
    private final ChatSessionService sessionService;
    private final ChatRagDecisionEngine decisionEngine;
    private final ChatRagTelemetryService telemetryService;

    public ChatTurnService(ChatTurnContextFactory contextFactory,
                           ChatRagFlowService ragFlowService,
                           ChatAssistantService assistantService,
                           ChatHistoryService historyService,
                           ChatSessionService sessionService,
                           ChatRagDecisionEngine decisionEngine,
                           ChatRagTelemetryService telemetryService) {
        this.contextFactory = contextFactory;
        this.ragFlowService = ragFlowService;
        this.assistantService = assistantService;
        this.historyService = historyService;
        this.sessionService = sessionService;
        this.decisionEngine = decisionEngine;
        this.telemetryService = telemetryService;
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
        // 1. Fija sesion, historial, adjuntos y plan heuristico del turno.
        ChatTurnContext context = contextFactory.create(
                username,
                maybeSessionId,
                userText,
                requestedModel,
                externalUserId,
                media
        );
        // 2. Decide si el turno usa RAG y con que fuerza entra al contexto recuperado.
        ChatRagContext ragContext = ragFlowService.resolve(context);
        // 3. Genera la respuesta final del asistente aplicando guardrails y retries si corresponden.
        ChatAssistantOutcome outcome = assistantService.answer(context, ragContext);
        ChatRagDecisionEngine.AnswerVerification answerVerification = ChatRagDecisionEngine.AnswerVerification.skip("post-check-not-needed");

        if (!ragContext.ragUsed() && !ragContext.missingEvidence()) {
            answerVerification = decisionEngine.verifyDirectAnswer(
                    context.userText(),
                    outcome.assistantText(),
                    context.turnPlan()
            );
            telemetryService.recordPostCheck(answerVerification, answerVerification.retryWithRag());
            logPostAnswerVerification(answerVerification, context);
            if (answerVerification.retryWithRag()) {
                ragContext = ragFlowService.resolveForced(context, answerVerification.reason());
                outcome = assistantService.answer(context, ragContext);
            }
        }

        // 4. Persiste la salida del asistente y enlaza fuentes cuando hubo grounding real.
        ChatMessage assistantMsg = historyService.saveAssistantMessage(context.session(), outcome.assistantText());
        if (ragContext.ragUsed()) {
            historyService.persistSources(assistantMsg, ragContext.scored());
        }

        // 5. Actualiza metadata operativa de la sesion y resume el resultado para el cliente.
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
        return new ChatResponse(
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
}


