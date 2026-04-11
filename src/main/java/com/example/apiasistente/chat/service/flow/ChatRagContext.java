package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.rag.dto.SourceDto;
import com.example.apiasistente.rag.service.RagService;

import java.util.List;

/**
 * Contexto RAG resuelto para un turno de chat.
 */
record ChatRagContext(List<RagService.ScoredChunk> scored,
                      List<SourceDto> sources,
                      ChatGroundingService.GroundingDecision groundingDecision,
                      boolean hasRagContext,
                      ChatGroundingService.RagRoute ragRoute,
                      boolean ragUsed,
                      boolean weakRagRoute,
                      boolean enforceGrounding,
                      String fallbackMessage,
                      RagService.RetrievalStats retrievalStats,
                      boolean missingEvidence) {

    ChatRagContext(List<RagService.ScoredChunk> scored,
                   List<SourceDto> sources,
                   ChatGroundingService.GroundingDecision groundingDecision,
                   boolean hasRagContext,
                   ChatGroundingService.RagRoute ragRoute,
                   boolean ragUsed,
                   boolean weakRagRoute,
                   boolean enforceGrounding,
                   String fallbackMessage) {
        this(
                scored,
                sources,
                groundingDecision,
                hasRagContext,
                ragRoute,
                ragUsed,
                weakRagRoute,
                enforceGrounding,
                fallbackMessage,
                RagService.RetrievalStats.empty(List.of(), 0.0, 0, 0.0),
                false
        );
    }

    static ChatRagContext noRag(ChatGroundingService groundingService) {
        return noRag(groundingService, RagService.RetrievalStats.empty(List.of(), 0.0, 0, 0.0));
    }

    static ChatRagContext noRag(ChatGroundingService groundingService, RagService.RetrievalStats retrievalStats) {
        ChatGroundingService.GroundingDecision defaultDecision =
                new ChatGroundingService.GroundingDecision(true, 1.0, 0, 1.0);
        return new ChatRagContext(
                List.of(),
                List.of(),
                defaultDecision,
                false,
                ChatGroundingService.RagRoute.NO_RAG,
                false,
                false,
                groundingService.shouldEnforceGrounding(false),
                groundingService.fallbackMessage(),
                retrievalStats,
                false
        );
    }

    static ChatRagContext noEvidence(ChatGroundingService groundingService, RagService.RetrievalStats retrievalStats) {
        ChatGroundingService.GroundingDecision defaultDecision =
                new ChatGroundingService.GroundingDecision(false, 0.0, 0, 0.0);
        return new ChatRagContext(
                List.of(),
                List.of(),
                defaultDecision,
                false,
                ChatGroundingService.RagRoute.NO_RAG,
                false,
                false,
                false,
                groundingService.noEvidenceMessage(),
                retrievalStats,
                true
        );
    }

    static ChatRagContext retrievalUnavailable(ChatGroundingService groundingService,
                                               RagService.RetrievalStats retrievalStats) {
        ChatGroundingService.GroundingDecision defaultDecision =
                new ChatGroundingService.GroundingDecision(false, 0.0, 0, 0.0);
        return new ChatRagContext(
                List.of(),
                List.of(),
                defaultDecision,
                false,
                ChatGroundingService.RagRoute.NO_RAG,
                false,
                false,
                false,
                groundingService.retrievalUnavailableMessage(),
                retrievalStats,
                true
        );
    }
}
