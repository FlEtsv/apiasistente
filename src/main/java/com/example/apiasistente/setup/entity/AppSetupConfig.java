package com.example.apiasistente.setup.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Configuracion de instalacion inicial editable desde UI.
 */
@Entity
@Table(name = "app_setup_config")
public class AppSetupConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private boolean configured;

    @Column(nullable = false, length = 320)
    private String ollamaBaseUrl;

    @Column(nullable = false, length = 140)
    private String chatModel;

    @Column(nullable = false, length = 140)
    private String fastChatModel;

    @Column(length = 140)
    private String visualModel;

    @Column(length = 180)
    private String imageModel;

    @Column(nullable = false, length = 140)
    private String embedModel;

    @Column(length = 140)
    private String responseGuardModel;

    @Column(nullable = false)
    private boolean scraperEnabled;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String scraperUrls;

    @Column(length = 120)
    private String scraperOwner;

    @Column(length = 160)
    private String scraperSource;

    @Column(length = 1000)
    private String scraperTags;

    @Column(nullable = false)
    private long scraperTickMs;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public boolean isConfigured() {
        return configured;
    }

    public void setConfigured(boolean configured) {
        this.configured = configured;
    }

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

    public long getScraperTickMs() {
        return scraperTickMs;
    }

    public void setScraperTickMs(long scraperTickMs) {
        this.scraperTickMs = scraperTickMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
