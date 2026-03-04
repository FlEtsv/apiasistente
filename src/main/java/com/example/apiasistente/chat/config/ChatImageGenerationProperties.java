package com.example.apiasistente.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuracion para generación de imágenes del chat.
 */
@Component
@ConfigurationProperties(prefix = "chat.image-generation")
public class ChatImageGenerationProperties {

    private boolean enabled = true;
    private String baseUrl = "https://image.pollinations.ai/prompt";
    private int width = 1024;
    private int height = 1024;
    private int timeoutMs = 90000;
    private int maxPromptChars = 600;
    private String storageDir = "data/chat-generated-images";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxPromptChars() {
        return maxPromptChars;
    }

    public void setMaxPromptChars(int maxPromptChars) {
        this.maxPromptChars = maxPromptChars;
    }

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }
}
