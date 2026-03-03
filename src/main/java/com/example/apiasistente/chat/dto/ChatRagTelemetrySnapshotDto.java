package com.example.apiasistente.chat.dto;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot agregado del motor de decisión RAG para exponerlo por API.
 */
public record ChatRagTelemetrySnapshotDto(
        Instant updatedAt,
        long totalTurns,
        long ragUsedTurns,
        long ragAvoidedTurns,
        long ragNeededTurns,
        long gateAttemptedTurns,
        long gateSkippedTurns,
        long forcedNoEvidenceTurns,
        long llmAssessments,
        long decisionCacheHits,
        long postChecksReviewed,
        long postCheckRetries,
        double ragUsedRate,
        double ragAvoidedRate,
        double cacheHitRate,
        double postCheckRetryRate,
        double avgDecisionConfidence,
        double avgHeuristicConfidence,
        double avgTurnConfidence,
        double avgRetrievalPhaseMs,
        double avgEmbeddingTimeMs,
        List<ChatRagTelemetryEventDto> recentEvents
) {
}
