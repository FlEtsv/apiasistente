package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.dto.ChatRagTelemetryEventDto;
import com.example.apiasistente.chat.dto.ChatRagTelemetrySnapshotDto;
import com.example.apiasistente.chat.service.ChatPromptSignals;
import com.example.apiasistente.chat.service.ChatTurnPlanner;
import com.example.apiasistente.rag.service.RagService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Agregador en memoria para observar cómo decide el sistema cuándo usar RAG.
 *
 * Se alimenta desde el gate, retrieval y post-check del turno.
 * No sustituye métricas externas; da una vista operativa inmediata para la home.
 */
@Service
public class ChatRagTelemetryService {

    private final Deque<ChatRagTelemetryEventDto> recentEvents = new ArrayDeque<>();

    @Value("${chat.rag-decision.max-events:80}")
    private int maxEvents;

    private long totalTurns;
    private long ragUsedTurns;
    private long ragAvoidedTurns;
    private long ragNeededTurns;
    private long gateAttemptedTurns;
    private long gateSkippedTurns;
    private long forcedNoEvidenceTurns;
    private long llmAssessments;
    private long decisionCacheHits;
    private long postChecksReviewed;
    private long postCheckRetries;
    private long decisionSamples;
    private long retrievalSamples;
    private long turnConfidenceSamples;
    private double sumDecisionConfidence;
    private double sumHeuristicConfidence;
    private double sumTurnConfidence;
    private double sumRetrievalPhaseMs;
    private double sumEmbeddingTimeMs;
    private Instant updatedAt = Instant.EPOCH;

    public synchronized void recordGate(ChatPromptSignals.RagDecision ragDecision,
                                        ChatRagGateService.GateDecision gateDecision) {
        if (gateDecision == null) {
            return;
        }
        if (gateDecision.attemptRag()) {
            gateAttemptedTurns++;
        } else {
            gateSkippedTurns++;
        }
        if (gateDecision.forceNoEvidence()) {
            forcedNoEvidenceTurns++;
        }

        ChatRagDecisionEngine.DecisionAssessment assessment = gateDecision.decisionAssessment();
        decisionSamples++;
        sumDecisionConfidence += assessment.confidence();
        sumHeuristicConfidence += assessment.heuristicConfidence();
        if (assessment.usedLlm()) {
            llmAssessments++;
        }
        if (assessment.cacheHit()) {
            decisionCacheHits++;
        }

        addEvent(
                "gate",
                gateDecision.attemptRag() ? "attempt" : "skip",
                gateDecision.reason(),
                "mode=" + (ragDecision == null ? "OFF" : ragDecision.mode())
                        + " type=" + assessment.queryType().value()
                        + " conf=" + formatDouble(assessment.confidence())
                        + " source=" + assessment.source()
        );
    }

    public synchronized void recordRetrieval(ChatPromptSignals.RagDecision ragDecision,
                                             RagService.RetrievalStats stats,
                                             double retrievalElapsedMs) {
        retrievalSamples++;
        sumRetrievalPhaseMs += Math.max(0.0, retrievalElapsedMs);
        sumEmbeddingTimeMs += Math.max(0.0, stats == null ? 0.0 : stats.queryEmbeddingTimeMs());

        addEvent(
                "retrieval",
                stats != null && stats.topKReturned() > 0 ? "evidence" : "empty",
                "retrieval",
                "mode=" + (ragDecision == null ? "OFF" : ragDecision.mode())
                        + " phase_ms=" + formatDouble(retrievalElapsedMs)
                        + " embed_ms=" + formatDouble(stats == null ? 0.0 : stats.queryEmbeddingTimeMs())
        );
    }

    public synchronized void recordPostCheck(ChatRagDecisionEngine.AnswerVerification verification,
                                             boolean retryTriggered) {
        if (verification == null || !verification.reviewed()) {
            return;
        }
        postChecksReviewed++;
        if (retryTriggered) {
            postCheckRetries++;
        }
        addEvent(
                "post-check",
                retryTriggered ? "retry-rag" : "ok",
                verification.reason(),
                "confidence=" + formatDouble(verification.confidence())
        );
    }

    public synchronized void recordTurnResult(boolean ragUsed,
                                              boolean ragNeeded,
                                              double confidence,
                                              ChatTurnPlanner.ReasoningLevel reasoningLevel) {
        totalTurns++;
        if (ragUsed) {
            ragUsedTurns++;
        } else {
            ragAvoidedTurns++;
        }
        if (ragNeeded) {
            ragNeededTurns++;
        }
        turnConfidenceSamples++;
        sumTurnConfidence += normalize(confidence);

        addEvent(
                "turn",
                ragUsed ? "rag-used" : "rag-avoided",
                ragNeeded ? "rag-needed" : "rag-optional",
                "confidence=" + formatDouble(confidence)
                        + " reasoning=" + (reasoningLevel == null ? "MEDIUM" : reasoningLevel.name())
        );
    }

    public synchronized ChatRagTelemetrySnapshotDto snapshot() {
        return new ChatRagTelemetrySnapshotDto(
                Instant.EPOCH.equals(updatedAt) ? null : updatedAt,
                totalTurns,
                ragUsedTurns,
                ragAvoidedTurns,
                ragNeededTurns,
                gateAttemptedTurns,
                gateSkippedTurns,
                forcedNoEvidenceTurns,
                llmAssessments,
                decisionCacheHits,
                postChecksReviewed,
                postCheckRetries,
                rate(ragUsedTurns, totalTurns),
                rate(ragAvoidedTurns, totalTurns),
                rate(decisionCacheHits, llmAssessments),
                rate(postCheckRetries, postChecksReviewed),
                average(sumDecisionConfidence, decisionSamples),
                average(sumHeuristicConfidence, decisionSamples),
                average(sumTurnConfidence, turnConfidenceSamples),
                average(sumRetrievalPhaseMs, retrievalSamples),
                average(sumEmbeddingTimeMs, retrievalSamples),
                List.copyOf(recentEvents)
        );
    }

    private void addEvent(String type, String outcome, String reason, String detail) {
        updatedAt = Instant.now();
        recentEvents.addFirst(new ChatRagTelemetryEventDto(
                updatedAt,
                normalizeText(type, "event"),
                normalizeText(outcome, "info"),
                normalizeText(reason, "-"),
                normalizeText(detail, "-")
        ));
        int limit = Math.max(10, maxEvents);
        while (recentEvents.size() > limit) {
            recentEvents.removeLast();
        }
    }

    private double average(double sum, long samples) {
        if (samples <= 0) {
            return 0.0;
        }
        return sum / samples;
    }

    private double rate(long value, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return (double) value / (double) total;
    }

    private double normalize(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private String formatDouble(double value) {
        return String.format(Locale.US, "%.3f", Math.max(0.0, value));
    }

    private String normalizeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
