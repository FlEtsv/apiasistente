package com.example.apiasistente.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ollama")
public class OllamaProperties {
    private String baseUrl;
    private String chatModel;
    private String embedModel;
    private boolean stream;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }

    public String getEmbedModel() { return embedModel; }
    public void setEmbedModel(String embedModel) { this.embedModel = embedModel; }

    public boolean isStream() { return stream; }
    public void setStream(boolean stream) { this.stream = stream; }
}
