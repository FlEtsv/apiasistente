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

    /**
     * Activa el modo aislado por usuario externo.
     * Solo permitido para API keys especiales.
     */
    private boolean specialMode;

    /**
     * Identificador de usuario final en la app externa (ej: cliente, accountId, userId).
     * Se usa como namespace de sesion cuando specialMode = true.
     */
    private String externalUserId;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public boolean isSpecialMode() { return specialMode; }
    public void setSpecialMode(boolean specialMode) { this.specialMode = specialMode; }

    public String getExternalUserId() { return externalUserId; }
    public void setExternalUserId(String externalUserId) { this.externalUserId = externalUserId; }
}
