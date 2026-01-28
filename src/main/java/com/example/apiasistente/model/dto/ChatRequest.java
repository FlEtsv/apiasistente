package com.example.apiasistente.model.dto;

import jakarta.validation.constraints.NotBlank;

public class ChatRequest {
    private String sessionId;

    @NotBlank
    private String message;

    /**
     * Modelo solicitado por el cliente: "default", "fast" o nombre exacto del modelo.
     */
    private String model;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
