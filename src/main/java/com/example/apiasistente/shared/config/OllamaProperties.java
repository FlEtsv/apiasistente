package com.example.apiasistente.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades de configuracion para Ollama.
 */
@ConfigurationProperties(prefix = "ollama")
public class OllamaProperties {
    private String baseUrl;
    private String chatModel;
    private String fastChatModel;
    private String visualModel;
    private String imageModel;
    private String responseGuardModel;
    private String embedModel;
    private Double temperature;
    private boolean stream;
    /** Ventana de contexto en tokens. 0 = default del modelo. Reduce VRAM/RAM en modelos grandes. */
    private int numCtx = 0;
    /** Tokens del system prompt a mantener fijos al rotar la ventana. 0 = default del modelo. */
    private int numKeep = 0;
    /** Timeout de conexion a Ollama en milisegundos. */
    private int connectTimeoutMs = 5000;
    /** Timeout de lectura (generacion) en milisegundos. 0 = sin limite (no recomendado). */
    private int readTimeoutMs = 120000;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }

    public String getFastChatModel() { return fastChatModel; }
    public void setFastChatModel(String fastChatModel) { this.fastChatModel = fastChatModel; }

    public String getVisualModel() { return visualModel; }
    public void setVisualModel(String visualModel) { this.visualModel = visualModel; }

    public String getImageModel() { return imageModel; }
    public void setImageModel(String imageModel) { this.imageModel = imageModel; }

    public String getResponseGuardModel() { return responseGuardModel; }
    public void setResponseGuardModel(String responseGuardModel) { this.responseGuardModel = responseGuardModel; }

    public String getEmbedModel() { return embedModel; }
    public void setEmbedModel(String embedModel) { this.embedModel = embedModel; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public boolean isStream() { return stream; }
    public void setStream(boolean stream) { this.stream = stream; }

    public int getNumCtx() { return numCtx; }
    public void setNumCtx(int numCtx) { this.numCtx = numCtx; }

    public int getNumKeep() { return numKeep; }
    public void setNumKeep(int numKeep) { this.numKeep = numKeep; }

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
}

