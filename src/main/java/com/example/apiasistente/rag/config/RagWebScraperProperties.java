package com.example.apiasistente.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuracion base del scraper web hacia RAG.
 */
@Component
@ConfigurationProperties(prefix = "rag.web-scraper")
public class RagWebScraperProperties {

    private boolean enabled = true;
    private long schedulerTickMs = 30_000L;
    private long initialDelayMs = 20_000L;
    private int timeoutMs = 12_000;
    private int maxCharsPerPage = 30_000;
    private int maxUrlsPerRun = 20;
    private String userAgent = "ApiAsistente-Scraper/1.0";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getSchedulerTickMs() {
        return schedulerTickMs;
    }

    public void setSchedulerTickMs(long schedulerTickMs) {
        this.schedulerTickMs = schedulerTickMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxCharsPerPage() {
        return maxCharsPerPage;
    }

    public void setMaxCharsPerPage(int maxCharsPerPage) {
        this.maxCharsPerPage = maxCharsPerPage;
    }

    public int getMaxUrlsPerRun() {
        return maxUrlsPerRun;
    }

    public void setMaxUrlsPerRun(int maxUrlsPerRun) {
        this.maxUrlsPerRun = maxUrlsPerRun;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }
}
