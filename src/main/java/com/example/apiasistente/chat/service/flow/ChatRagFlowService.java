package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.service.ChatPromptSignals;
import com.example.apiasistente.rag.dto.SourceDto;
import com.example.apiasistente.rag.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Encapsula la fase de retrieval del turno.
 * Decide si se consulta la base, como se arma la query y que nivel de grounding queda disponible.
 */
@Service
public class ChatRagFlowService {

    private static final Logger log = LoggerFactory.getLogger(ChatRagFlowService.class);
    private static final double SECOND_PASS_MIN_SCORE_DELTA = 0.02;

    private final ChatPromptBuilder promptBuilder;
    private final ChatHistoryService historyService;
    private final RagService ragService;
    private final ChatGroundingService groundingService;
    private final ChatRagGateService ragGateService;
    private final ChatRagTelemetryService telemetryService;

    @Value("${chat.rag-flow.override-gate-for-uncertain-preferred:true}")
    private boolean overrideGateForUncertainPreferred;

    @Value("${chat.rag-flow.min-query-chars-for-gate-override:14}")
    private int minQueryCharsForGateOverride;

    @Value("${chat.rag-flow.second-pass-on-empty-enabled:true}")
    private boolean secondPassOnEmptyEnabled;

    public ChatRagFlowService(ChatPromptBuilder promptBuilder,
                              ChatHistoryService historyService,
                              RagService ragService,
                              ChatGroundingService groundingService,
                              ChatRagGateService ragGateService,
                              ChatRagTelemetryService telemetryService) {
        this.promptBuilder = promptBuilder;
        this.historyService = historyService;
        this.ragService = ragService;
        this.groundingService = groundingService;
        this.ragGateService = ragGateService;
        this.telemetryService = telemetryService;
    }

    /**
     * Resuelve el contexto RAG efectivo del turno a partir del plan ya calculado.
     */
    public ChatRagContext resolve(ChatTurnContext context) {
        return resolve(context, context.turnPlan().ragDecision());
    }

    /**
     * Reintenta retrieval con una decision forzada cuando la verificacion posterior lo exige.
     */
    public ChatRagContext resolveForced(ChatTurnContext context, String reason) {
        ChatPromptSignals.RagDecision forcedDecision = ChatPromptSignals.RagDecision.required(
                "post-answer-rag: " + (reason == null ? "sin-razon" : reason.trim()),
                List.of("post-answer-verification")
        );
        return resolve(context, forcedDecision);
    }

    private ChatRagContext resolve(ChatTurnContext context, ChatPromptSignals.RagDecision ragDecision) {
        if (!ragDecision.enabled()) {
            return ChatRagContext.noRag(groundingService);
        }

        ChatRagGateService.GateDecision gateDecision = ragGateService.evaluate(
                context.turnPlan(),
                ragDecision,
                context.userText(),
                context.username(),
                context.normalizedExternalUserId(),
                hasDocumentMedia(context.preparedMedia())
        );
        logGateTelemetry(ragDecision, gateDecision);
        boolean gateOverride = shouldOverrideGateSkip(context, ragDecision, gateDecision);

        if (!gateDecision.attemptRag() && !gateOverride) {
            RagService.RetrievalStats stats = RagService.RetrievalStats.empty(gateDecision.owners(), 0.0, 0, 0.0);
            if (gateDecision.forceNoEvidence()) {
                return ChatRagContext.noEvidence(groundingService, stats);
            }
            return ChatRagContext.noRag(groundingService, stats);
        }
        if (gateOverride) {
            log.info(
                    "rag_gate_override sessionId={} original_reason={} query_type={} decision_confidence={} reason=uncertain-preferred-skip",
                    context.session().getId(),
                    gateDecision.reason(),
                    gateDecision.decisionAssessment().queryType(),
                    String.format(Locale.US, "%.3f", gateDecision.decisionAssessment().confidence())
            );
        }

        long retrievalStartNanos = System.nanoTime();

        // Usa el turno actual, historial corto y adjuntos textuales para construir una query de retrieval mas util.
        String retrievalQuery = promptBuilder.buildRetrievalQuery(
                context.userText(),
                historyService.recentUserTurnsForRetrieval(context.session().getId()),
                context.preparedMedia()
        );

        RagService.RetrievalResult retrieval;
        try {
            // Corpus unificado: retrieval sin filtro de propietario.
            retrieval = ragService.retrieveShared(retrievalQuery);
            retrieval = maybeRunSecondPassRetrieval(
                    context,
                    ragDecision,
                    gateDecision,
                    retrievalQuery,
                    retrieval
            );
        } catch (Exception e) {
            // Si retrieval falla, el turno degrada explicitamente y no responde "a ojo" desde el LLM.
            log.warn("rag_retrieval_error sessionId={} reason={} fallback=retrieval-unavailable",
                    context.session().getId(), e.getMessage());
            RagService.RetrievalStats stats = RagService.RetrievalStats.empty(gateDecision.owners(), 0.0, 0, 0.0);
            return ChatRagContext.retrievalUnavailable(groundingService, stats);
        }

        logRetrievalTelemetry(
                ragDecision,
                retrieval.stats(),
                (System.nanoTime() - retrievalStartNanos) / 1_000_000.0
        );

        // Cuando el turno exige evidencia y no aparece contexto, el flujo debe caer a mensaje controlado.
        if (!retrieval.hasEvidence()) {
            if (ragDecision.requiresEvidence()) {
                return ChatRagContext.noEvidence(groundingService, retrieval.stats());
            }
            return ChatRagContext.noRag(groundingService, retrieval.stats());
        }

        List<RagService.ScoredChunk> scored = retrieval.contextChunks();
        List<SourceDto> sources = ragService.toSourceDtos(scored);
        ChatGroundingService.GroundingDecision groundingDecision = groundingService.assessGrounding(scored);
        boolean hasRagContext = !scored.isEmpty();
        ChatGroundingService.RagRoute ragRoute = groundingService.resolveRagRoute(true, groundingDecision);
        boolean ragUsed = ragRoute != ChatGroundingService.RagRoute.NO_RAG;
        boolean weakRagRoute = ragRoute == ChatGroundingService.RagRoute.WEAK;
        boolean enforceGrounding = groundingService.shouldEnforceGrounding(ragUsed);
        String fallbackMessage = groundingService.fallbackMessage();

        if (log.isDebugEnabled() && ragUsed) {
            log.debug(
                    "RAG routing route={} hardWeak={} supportingChunks={} topScore={} confidence={}",
                    ragRoute,
                    groundingService.isWeakRagGrounding(groundingDecision),
                    groundingDecision.supportingChunks(),
                    String.format(Locale.US, "%.3f", groundingDecision.topScore()),
                    String.format(Locale.US, "%.3f", groundingDecision.confidence())
            );
        }

        return new ChatRagContext(
                scored,
                sources,
                groundingDecision,
                hasRagContext,
                ragRoute,
                ragUsed,
                weakRagRoute,
                enforceGrounding,
                fallbackMessage,
                retrieval.stats(),
                false
        );
    }

    private RagService.RetrievalResult maybeRunSecondPassRetrieval(ChatTurnContext context,
                                                                    ChatPromptSignals.RagDecision ragDecision,
                                                                    ChatRagGateService.GateDecision gateDecision,
                                                                    String primaryQuery,
                                                                    RagService.RetrievalResult primaryResult) {
        if (!shouldRunSecondPass(context, ragDecision, gateDecision, primaryQuery, primaryResult)) {
            return primaryResult;
        }

        String secondPassQuery = buildFallbackRetrievalQuery(context.userText());
        try {
            RagService.RetrievalResult secondPassResult = ragService.retrieveShared(secondPassQuery);
            RagService.RetrievalResult selected = selectBestRetrieval(primaryResult, secondPassResult);
            log.info(
                    "rag_retrieval_second_pass sessionId={} selected={} primary_has_evidence={} second_has_evidence={} primary_topk={} second_topk={} primary_max_similarity={} second_max_similarity={}",
                    context.session().getId(),
                    selected == secondPassResult ? "second-pass" : "primary",
                    primaryResult.hasEvidence(),
                    secondPassResult.hasEvidence(),
                    primaryResult.stats().topKReturned(),
                    secondPassResult.stats().topKReturned(),
                    String.format(Locale.US, "%.3f", primaryResult.stats().maxSimilarity()),
                    String.format(Locale.US, "%.3f", secondPassResult.stats().maxSimilarity())
            );
            return selected;
        } catch (Exception ex) {
            log.warn(
                    "rag_retrieval_second_pass_error sessionId={} reason={} fallback=primary-query",
                    context.session().getId(),
                    ex.getMessage()
            );
            return primaryResult;
        }
    }

    private boolean shouldRunSecondPass(ChatTurnContext context,
                                        ChatPromptSignals.RagDecision ragDecision,
                                        ChatRagGateService.GateDecision gateDecision,
                                        String primaryQuery,
                                        RagService.RetrievalResult primaryResult) {
        if (!secondPassOnEmptyEnabled || primaryResult == null || primaryResult.hasEvidence()) {
            return false;
        }
        if (context == null || !hasText(context.userText())) {
            return false;
        }
        if (hasDocumentMedia(context.preparedMedia())) {
            return false;
        }

        String secondPassQuery = buildFallbackRetrievalQuery(context.userText());
        if (!hasText(secondPassQuery) || sameRetrievalQuery(primaryQuery, secondPassQuery)) {
            return false;
        }

        if (ragDecision != null && ragDecision.requiresEvidence()) {
            return true;
        }
        if (gateDecision == null) {
            return false;
        }
        ChatRagDecisionEngine.DecisionAssessment assessment = gateDecision.decisionAssessment();
        return assessment.needsRag()
                || assessment.needsExternalContext()
                || assessment.verifyAfterDirectAnswer();
    }

    private RagService.RetrievalResult selectBestRetrieval(RagService.RetrievalResult primaryResult,
                                                           RagService.RetrievalResult secondPassResult) {
        if (secondPassResult == null) {
            return primaryResult;
        }
        if (primaryResult == null) {
            return secondPassResult;
        }

        if (secondPassResult.hasEvidence() && !primaryResult.hasEvidence()) {
            return secondPassResult;
        }
        if (primaryResult.hasEvidence() && !secondPassResult.hasEvidence()) {
            return primaryResult;
        }

        if (secondPassResult.stats().maxSimilarity() > primaryResult.stats().maxSimilarity() + SECOND_PASS_MIN_SCORE_DELTA) {
            return secondPassResult;
        }
        if (secondPassResult.stats().topKReturned() > primaryResult.stats().topKReturned()) {
            return secondPassResult;
        }
        return primaryResult;
    }

    private boolean shouldOverrideGateSkip(ChatTurnContext context,
                                           ChatPromptSignals.RagDecision ragDecision,
                                           ChatRagGateService.GateDecision gateDecision) {
        if (!overrideGateForUncertainPreferred) {
            return false;
        }
        if (ragDecision == null || ragDecision.requiresEvidence()) {
            return false;
        }
        if (gateDecision == null || gateDecision.attemptRag() || gateDecision.forceNoEvidence()) {
            return false;
        }
        String query = collapseSpaces(context == null ? "" : context.userText());
        if (query.length() < Math.max(1, minQueryCharsForGateOverride)) {
            return false;
        }
        ChatRagDecisionEngine.DecisionAssessment assessment = gateDecision.decisionAssessment();
        if (assessment.needsRag() || assessment.needsExternalContext()) {
            return true;
        }
        return assessment.verifyAfterDirectAnswer() && assessment.confidence() < 0.75;
    }

    private String buildFallbackRetrievalQuery(String userText) {
        return collapseSpaces(userText);
    }

    private boolean sameRetrievalQuery(String left, String right) {
        return normalizeForQueryComparison(left).equals(normalizeForQueryComparison(right));
    }

    private String normalizeForQueryComparison(String value) {
        return collapseSpaces(value).toLowerCase(Locale.ROOT);
    }

    /**
     * Emite telemetria de retrieval para diagnosticar calidad de contexto y routing RAG.
     */
    private void logRetrievalTelemetry(ChatPromptSignals.RagDecision ragDecision,
                                       RagService.RetrievalStats stats,
                                       double retrievalElapsedMs) {
        telemetryService.recordRetrieval(ragDecision, stats, retrievalElapsedMs);
        log.info(
                "rag_retrieval rag_decision={} rag_mode={} retrieval_phase_ms={} query_embedding_time_ms={} topK_returned={} max_similarity={} avg_similarity={} evidence_threshold={} chunks_used_ids={} source_docs={} context_tokens={}",
                ragDecision.enabled() ? "ON" : "OFF",
                ragDecision.mode(),
                String.format(Locale.US, "%.2f", retrievalElapsedMs),
                String.format(Locale.US, "%.2f", stats.queryEmbeddingTimeMs()),
                stats.topKReturned(),
                String.format(Locale.US, "%.3f", stats.maxSimilarity()),
                String.format(Locale.US, "%.3f", stats.avgSimilarity()),
                String.format(Locale.US, "%.3f", stats.evidenceThreshold()),
                stats.chunksUsedIds(),
                stats.sourceDocs(),
                stats.contextTokens()
        );
    }

    private void logGateTelemetry(ChatPromptSignals.RagDecision ragDecision,
                                  ChatRagGateService.GateDecision gateDecision) {
        telemetryService.recordGate(ragDecision, gateDecision);
        log.info(
                "rag_gate rag_decision={} rag_mode={} attempt_rag={} force_no_evidence={} reason={} owners={} active_documents={} active_chunks={} matched_terms={} query_type={} decision_source={} decision_confidence={} heuristic_confidence={} needs_external_context={} used_llm={} cache_hit={} verify_after_direct_answer={}",
                ragDecision.enabled() ? "ON" : "OFF",
                ragDecision.mode(),
                gateDecision.attemptRag(),
                gateDecision.forceNoEvidence(),
                gateDecision.reason(),
                gateDecision.owners(),
                gateDecision.activeDocuments(),
                gateDecision.activeChunks(),
                gateDecision.matchedTerms(),
                gateDecision.decisionAssessment().queryType(),
                gateDecision.decisionAssessment().source(),
                String.format(Locale.US, "%.3f", gateDecision.decisionAssessment().confidence()),
                String.format(Locale.US, "%.3f", gateDecision.decisionAssessment().heuristicConfidence()),
                gateDecision.decisionAssessment().needsExternalContext(),
                gateDecision.decisionAssessment().usedLlm(),
                gateDecision.decisionAssessment().cacheHit(),
                gateDecision.decisionAssessment().verifyAfterDirectAnswer()
        );
    }

    /**
     * Ayuda local para validar texto no vacio sin duplicar condiciones.
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String collapseSpaces(String value) {
        if (!hasText(value)) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private boolean hasDocumentMedia(List<ChatMediaService.PreparedMedia> media) {
        if (media == null || media.isEmpty()) {
            return false;
        }
        return media.stream().anyMatch(item -> hasText(item.documentText()));
    }
}
