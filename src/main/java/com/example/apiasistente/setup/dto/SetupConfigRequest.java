package com.example.apiasistente.setup.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload de guardado para el wizard de instalacion.
 */
public class SetupConfigRequest {

    @NotBlank
    private String ollamaBaseUrl;

    @NotBlank
    private String chatModel;

    @NotBlank
    private String fastChatModel;

    private String visualModel;
    private String imageModel;

    @NotBlank
    private String embedModel;

    private String responseGuardModel;

    private boolean scraperEnabled;
    private String scraperUrls;
    private String scraperOwner;
    private String scraperSource;
    private String scraperTags;
    private Long scraperTickMs;

    public String getOllamaBaseUrl() {
        return ollamaBaseUrl;
    }

    public void setOllamaBaseUrl(String ollamaBaseUrl) {
        this.ollamaBaseUrl = ollamaBaseUrl;
    }

    public String getChatModel() {
        return chatModel;
    }

    public void setChatModel(String chatModel) {
        this.chatModel = chatModel;
    }

    public String getFastChatModel() {
        return fastChatModel;
    }

    public void setFastChatModel(String fastChatModel) {
        this.fastChatModel = fastChatModel;
    }

    public String getVisualModel() {
        return visualModel;
    }

    public void setVisualModel(String visualModel) {
        this.visualModel = visualModel;
    }

    public String getImageModel() {
        return imageModel;
    }

    public void setImageModel(String imageModel) {
        this.imageModel = imageModel;
    }

    public String getEmbedModel() {
        return embedModel;
    }

    public void setEmbedModel(String embedModel) {
        this.embedModel = embedModel;
    }

    public String getResponseGuardModel() {
        return responseGuardModel;
    }

    public void setResponseGuardModel(String responseGuardModel) {
        this.responseGuardModel = responseGuardModel;
    }

    public boolean isScraperEnabled() {
        return scraperEnabled;
    }

    public void setScraperEnabled(boolean scraperEnabled) {
        this.scraperEnabled = scraperEnabled;
    }

    public String getScraperUrls() {
        return scraperUrls;
    }

    public void setScraperUrls(String scraperUrls) {
        this.scraperUrls = scraperUrls;
    }

    public String getScraperOwner() {
        return scraperOwner;
    }

    public void setScraperOwner(String scraperOwner) {
        this.scraperOwner = scraperOwner;
    }

    public String getScraperSource() {
        return scraperSource;
    }

    public void setScraperSource(String scraperSource) {
        this.scraperSource = scraperSource;
    }

    public String getScraperTags() {
        return scraperTags;
    }

    public void setScraperTags(String scraperTags) {
        this.scraperTags = scraperTags;
    }

    public Long getScraperTickMs() {
        return scraperTickMs;
    }

    public void setScraperTickMs(Long scraperTickMs) {
        this.scraperTickMs = scraperTickMs;
    }
}
