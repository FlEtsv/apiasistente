package com.example.apiasistente.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuracion para generacion de imagenes del chat.
 */
@Component
@ConfigurationProperties(prefix = "chat.image-generation")
public class ChatImageGenerationProperties {

    private boolean enabled = true;
    private String baseUrl = "http://127.0.0.1:3000";
    private String promptPath = "/prompt";
    private int width = 1024;
    private int height = 1024;
    private int timeoutMs = 120000;
    private int maxPromptChars = 600;
    private String storageDir = "data/chat-generated-images";
    private String checkpoint = "flux1-schnell-fp8.safetensors";
    private int steps = 4;
    private double cfgScale = 1.0;
    private String samplerName = "euler";
    private String scheduler = "simple";
    private double denoise = 1.0;
    private double img2imgDenoise = 0.8;
    private long seed = -1L;
    private String negativePrompt = "";
    private String convertFormat = "";
    private int convertQuality = 85;

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

    public String getPromptPath() {
        return promptPath;
    }

    public void setPromptPath(String promptPath) {
        this.promptPath = promptPath;
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

    public String getCheckpoint() {
        return checkpoint;
    }

    public void setCheckpoint(String checkpoint) {
        this.checkpoint = checkpoint;
    }

    public int getSteps() {
        return steps;
    }

    public void setSteps(int steps) {
        this.steps = steps;
    }

    public double getCfgScale() {
        return cfgScale;
    }

    public void setCfgScale(double cfgScale) {
        this.cfgScale = cfgScale;
    }

    public String getSamplerName() {
        return samplerName;
    }

    public void setSamplerName(String samplerName) {
        this.samplerName = samplerName;
    }

    public String getScheduler() {
        return scheduler;
    }

    public void setScheduler(String scheduler) {
        this.scheduler = scheduler;
    }

    public double getDenoise() {
        return denoise;
    }

    public void setDenoise(double denoise) {
        this.denoise = denoise;
    }

    public double getImg2imgDenoise() {
        return img2imgDenoise;
    }

    public void setImg2imgDenoise(double img2imgDenoise) {
        this.img2imgDenoise = img2imgDenoise;
    }

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    public String getNegativePrompt() {
        return negativePrompt;
    }

    public void setNegativePrompt(String negativePrompt) {
        this.negativePrompt = negativePrompt;
    }

    public String getConvertFormat() {
        return convertFormat;
    }

    public void setConvertFormat(String convertFormat) {
        this.convertFormat = convertFormat;
    }

    public int getConvertQuality() {
        return convertQuality;
    }

    public void setConvertQuality(int convertQuality) {
        this.convertQuality = convertQuality;
    }
}
