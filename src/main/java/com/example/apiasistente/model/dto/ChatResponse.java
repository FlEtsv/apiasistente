package com.example.apiasistente.model.dto;

import java.util.List;

public class ChatResponse {
    private String sessionId;
    private String reply;
    private List<SourceDto> sources;

    public ChatResponse(String sessionId, String reply, List<SourceDto> sources) {
        this.sessionId = sessionId;
        this.reply = reply;
        this.sources = sources;
    }

    public String getSessionId() { return sessionId; }
    public String getReply() { return reply; }
    public List<SourceDto> getSources() { return sources; }
}
