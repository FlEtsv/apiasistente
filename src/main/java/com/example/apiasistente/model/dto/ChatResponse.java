package com.example.apiasistente.model.dto;

import java.util.List;

public class ChatResponse {
    private String sessionId;
    private String reply;
    private List<SourceDto> sources;
    private boolean safe;
    private double confidence;
    private int groundedSources;
    private boolean ragUsed;

    public ChatResponse(String sessionId, String reply, List<SourceDto> sources) {
        this(
                sessionId,
                reply,
                sources,
                true,
                1.0,
                sources == null ? 0 : sources.size(),
                sources != null && !sources.isEmpty()
        );
    }

    public ChatResponse(String sessionId,
                        String reply,
                        List<SourceDto> sources,
                        boolean safe,
                        double confidence,
                        int groundedSources) {
        this(sessionId, reply, sources, safe, confidence, groundedSources, sources != null && !sources.isEmpty());
    }

    public ChatResponse(String sessionId,
                        String reply,
                        List<SourceDto> sources,
                        boolean safe,
                        double confidence,
                        int groundedSources,
                        boolean ragUsed) {
        this.sessionId = sessionId;
        this.reply = reply;
        this.sources = sources;
        this.safe = safe;
        this.confidence = confidence;
        this.groundedSources = groundedSources;
        this.ragUsed = ragUsed;
    }

    public String getSessionId() { return sessionId; }
    public String getReply() { return reply; }
    public List<SourceDto> getSources() { return sources; }
    public boolean isSafe() { return safe; }
    public double getConfidence() { return confidence; }
    public int getGroundedSources() { return groundedSources; }
    public boolean isRagUsed() { return ragUsed; }
}
