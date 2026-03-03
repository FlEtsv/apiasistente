package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.service.ChatPromptSignals;
import com.example.apiasistente.rag.dto.SourceDto;
import com.example.apiasistente.rag.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final ChatPromptBuilder promptBuilder;
    private final ChatHistoryService historyService;
    private final RagService ragService;
    private final ChatGroundingService groundingService;
    private final ChatRagGateService ragGateService;

    public ChatRagFlowService(ChatPromptBuilder promptBuilder,
                              ChatHistoryService historyService,
                              RagService ragService,
                              ChatGroundingService groundingService,
                              ChatRagGateService ragGateService) {
        this.promptBuilder = promptBuilder;
        this.historyService = historyService;
        this.ragService = ragService;
        this.groundingService = groundingService;
        this.ragGateService = ragGateService;
    }

    /**
     * Resuelve el contexto RAG efectivo del turno a partir del plan ya calculado.
     */
    public ChatRagContext resolve(ChatTurnContext context) {
        ChatPromptSignals.RagDecision ragDecision = context.turnPlan().ragDecision();
        if (!ragDecision.enabled()) {
            return ChatRagContext.noRag(groundingService);
        }

        ChatRagGateService.GateDecision gateDecision = ragGateService.evaluate(
                ragDecision,
                context.userText(),
                context.username(),
                context.normalizedExternalUserId(),
                hasDocumentMedia(context.preparedMedia())
        );
        logGateTelemetry(ragDecision, gateDecision);

        if (!gateDecision.attemptRag()) {
            RagService.RetrievalStats stats = RagService.RetrievalStats.empty(gateDecision.owners(), 0.0, 0, 0.0);
            if (gateDecision.forceNoEvidence()) {
                return ChatRagContext.noEvidence(groundingService, stats);
            }
            return ChatRagContext.noRag(groundingService, stats);
        }

        long retrievalStartNanos = System.nanoTime();

        // Usa el turno actual, historial corto y adjuntos textuales para construir una query de retrieval mas util.
        String retrievalQuery = promptBuilder.buildRetrievalQuery(
                context.userText(),
                historyService.recentUserTurnsForRetrieval(context.session().getId()),
                context.preparedMedia()
        );
        // Si hay usuario externo aislado se restringe el retrieval a su scope y al conocimiento global permitido.
        RagService.RetrievalResult retrieval = hasText(context.normalizedExternalUserId())
                ? ragService.retrieveForOwnerScopedAndGlobal(
                retrievalQuery,
                context.username(),
                context.normalizedExternalUserId()
        )
                : ragService.retrieveForOwnerOrGlobal(retrievalQuery, context.username());

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

    /**
     * Emite telemetria de retrieval para diagnosticar calidad de contexto y routing RAG.
     */
    private void logRetrievalTelemetry(ChatPromptSignals.RagDecision ragDecision,
                                       RagService.RetrievalStats stats,
                                       double retrievalElapsedMs) {
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
        log.info(
                "rag_gate rag_decision={} rag_mode={} attempt_rag={} force_no_evidence={} reason={} owners={} active_documents={} active_chunks={} matched_terms={}",
                ragDecision.enabled() ? "ON" : "OFF",
                ragDecision.mode(),
                gateDecision.attemptRag(),
                gateDecision.forceNoEvidence(),
                gateDecision.reason(),
                gateDecision.owners(),
                gateDecision.activeDocuments(),
                gateDecision.activeChunks(),
                gateDecision.matchedTerms()
        );
    }

    /**
     * Ayuda local para validar texto no vacio sin duplicar condiciones.
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean hasDocumentMedia(List<ChatMediaService.PreparedMedia> media) {
        if (media == null || media.isEmpty()) {
            return false;
        }
        return media.stream().anyMatch(item -> hasText(item.documentText()));
    }
}
