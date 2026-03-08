package com.example.apiasistente.chat.service.flow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Flujo de verificacion posterior cuando el turno respondio sin RAG.
 *
 * Si el verificador detecta riesgo de respuesta incompleta, fuerza un segundo pase con retrieval.
 */
@Service
public class ChatRagPostCheckFlowService {

    private static final Logger log = LoggerFactory.getLogger(ChatRagPostCheckFlowService.class);

    private final ChatRagDecisionEngine decisionEngine;
    private final ChatRagTelemetryService telemetryService;
    private final ChatRagFlowService ragFlowService;
    private final ChatAssistantService assistantService;

    public ChatRagPostCheckFlowService(ChatRagDecisionEngine decisionEngine,
                                       ChatRagTelemetryService telemetryService,
                                       ChatRagFlowService ragFlowService,
                                       ChatAssistantService assistantService) {
        this.decisionEngine = decisionEngine;
        this.telemetryService = telemetryService;
        this.ragFlowService = ragFlowService;
        this.assistantService = assistantService;
    }

    public PostCheckResult run(ChatTurnContext context,
                               ChatRagContext ragContext,
                               ChatAssistantOutcome outcome) {
        ChatRagDecisionEngine.AnswerVerification verification = ChatRagDecisionEngine.AnswerVerification.skip("post-check-not-needed");
        if (context == null || ragContext == null || outcome == null) {
            return new PostCheckResult(ragContext, outcome, verification);
        }

        ChatRagContext effectiveRagContext = ragContext;
        ChatAssistantOutcome effectiveOutcome = outcome;
        if (!effectiveRagContext.ragUsed() && !effectiveRagContext.missingEvidence()) {
            verification = decisionEngine.verifyDirectAnswer(
                    context.userText(),
                    effectiveOutcome.assistantText(),
                    context.turnPlan()
            );
            telemetryService.recordPostCheck(verification, verification.retryWithRag());
            logPostAnswerVerification(verification, context);

            if (verification.retryWithRag()) {
                effectiveRagContext = ragFlowService.resolveForced(context, verification.reason());
                effectiveOutcome = assistantService.answer(context, effectiveRagContext);
                log.info(
                        "chat_turn_stage stage=post_check_retry sessionId={} ragUsed={} route={} sourceCount={}",
                        context.session().getId(),
                        effectiveRagContext.ragUsed(),
                        effectiveRagContext.ragRoute(),
                        effectiveRagContext.sources().size()
                );
            }
        }

        return new PostCheckResult(effectiveRagContext, effectiveOutcome, verification);
    }

    private void logPostAnswerVerification(ChatRagDecisionEngine.AnswerVerification verification,
                                           ChatTurnContext context) {
        if (verification == null || context == null) {
            return;
        }
        String ragMode = context.turnPlan() == null
                ? "OFF"
                : context.turnPlan().ragDecision().mode().name();
        log.info(
                "rag_post_check reviewed={} retry_with_rag={} confidence={} reason={} intent={} rag_mode={}",
                verification.reviewed(),
                verification.retryWithRag(),
                String.format(Locale.US, "%.3f", verification.confidence()),
                verification.reason(),
                context.intentRoute(),
                ragMode
        );
    }

    public record PostCheckResult(ChatRagContext ragContext,
                                  ChatAssistantOutcome outcome,
                                  ChatRagDecisionEngine.AnswerVerification answerVerification) {

        public PostCheckResult {
            answerVerification = answerVerification == null
                    ? ChatRagDecisionEngine.AnswerVerification.skip("post-check-not-needed")
                    : answerVerification;
        }
    }
}
