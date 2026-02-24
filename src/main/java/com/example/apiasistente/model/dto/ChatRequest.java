package com.example.apiasistente.model.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public class ChatRequest {
    private String sessionId;

    @NotBlank
    private String message;

    /**
     * Modelo solicitado por el cliente: "default", "fast", "visual" o nombre exacto permitido.
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

    /**
     * Adjuntos de imagen/camara/documento para modo visual.
     */
    private List<ChatMediaInput> media;

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

    public List<ChatMediaInput> getMedia() { return media; }
    public void setMedia(List<ChatMediaInput> media) { this.media = media; }
}
