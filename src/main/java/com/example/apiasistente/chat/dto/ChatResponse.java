package com.example.apiasistente.chat.dto;

import com.example.apiasistente.rag.dto.SourceDto;

import java.util.List;

/**
 * Respuesta del chat.
 */
public class ChatResponse {
    private static final String DEFAULT_REASONING_LEVEL = "MEDIUM";
    private static final double DEFAULT_CONFIDENCE = 1.0;

    private String sessionId;
    private String reply;
    private List<SourceDto> sources;
    private boolean safe;
    private double confidence;
    private int groundedSources;
    private boolean ragUsed;
    private boolean ragNeeded;
    private String reasoningLevel;

    public ChatResponse(String sessionId, String reply, List<SourceDto> sources) {
        this(
                sessionId,
                reply,
                sources,
                true,
                DEFAULT_CONFIDENCE,
                defaultGroundedSources(sources),
                hasSources(sources),
                hasSources(sources),
                DEFAULT_REASONING_LEVEL
        );
    }

    public ChatResponse(String sessionId,
                        String reply,
                        List<SourceDto> sources,
                        boolean safe,
                        double confidence,
                        int groundedSources) {
        this(
                sessionId,
                reply,
                sources,
                safe,
                confidence,
                groundedSources,
                hasSources(sources),
                hasSources(sources),
                DEFAULT_REASONING_LEVEL
        );
    }

    public ChatResponse(String sessionId,
                        String reply,
                        List<SourceDto> sources,
                        boolean safe,
                        double confidence,
                        int groundedSources,
                        boolean ragUsed) {
        this(
                sessionId,
                reply,
                sources,
                safe,
                confidence,
                groundedSources,
                ragUsed,
                ragUsed,
                DEFAULT_REASONING_LEVEL
        );
    }

    public ChatResponse(String sessionId,
                        String reply,
                        List<SourceDto> sources,
                        boolean safe,
                        double confidence,
                        int groundedSources,
                        boolean ragUsed,
                        boolean ragNeeded,
                        String reasoningLevel) {
        this.sessionId = sessionId;
        this.reply = reply;
        this.sources = normalizeSources(sources);
        this.safe = safe;
        this.confidence = confidence;
        this.groundedSources = Math.max(0, groundedSources);
        this.ragUsed = ragUsed;
        this.ragNeeded = ragNeeded;
        this.reasoningLevel = normalizeReasoningLevel(reasoningLevel);
    }

    public String getSessionId() { return sessionId; }
    public String getReply() { return reply; }
    public List<SourceDto> getSources() { return sources; }
    public boolean isSafe() { return safe; }
    public double getConfidence() { return confidence; }
    public int getGroundedSources() { return groundedSources; }
    public boolean isRagUsed() { return ragUsed; }
    public boolean isRagNeeded() { return ragNeeded; }
    public String getReasoningLevel() { return reasoningLevel; }

    private static String normalizeReasoningLevel(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_REASONING_LEVEL;
        }
        return value.trim().toUpperCase();
    }

    private static int defaultGroundedSources(List<SourceDto> sources) {
        return normalizeSources(sources).size();
    }

    private static boolean hasSources(List<SourceDto> sources) {
        return sources != null && !sources.isEmpty();
    }

    private static List<SourceDto> normalizeSources(List<SourceDto> sources) {
        return sources == null ? List.of() : List.copyOf(sources);
    }
}

