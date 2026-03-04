package com.example.apiasistente.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Propiedades del robot de mantenimiento del RAG.
 */
@Component
@ConfigurationProperties(prefix = "rag.maintenance")
public class RagMaintenanceProperties {

    private boolean enabled = true;
    private boolean dryRun = false;
    private long intervalMs = 180000;
    private int pageSize = 40;
    private int maxEvents = 120;
    private int minDocumentChars = 30;
    private int minDocumentTokens = 4;
    private int minChunkChars = 20;
    private int minChunkTokens = 3;
    private int maxLineCopies = 1;
    private int warningReviewHours = 48;
    private int aiAutoApplyHours = 24;
    private int adminBacklogThreshold = 10;
    private int adminBacklogAiMaxPerPass = 3;
    private boolean aiRequireHealthyMonitoring = true;
    private int unusedDaysThreshold = 30;
    private int snippetChars = 600;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getMaxEvents() {
        return maxEvents;
    }

    public void setMaxEvents(int maxEvents) {
        this.maxEvents = maxEvents;
    }

    public int getMinDocumentChars() {
        return minDocumentChars;
    }

    public void setMinDocumentChars(int minDocumentChars) {
        this.minDocumentChars = minDocumentChars;
    }

    public int getMinDocumentTokens() {
        return minDocumentTokens;
    }

    public void setMinDocumentTokens(int minDocumentTokens) {
        this.minDocumentTokens = minDocumentTokens;
    }

    public int getMinChunkChars() {
        return minChunkChars;
    }

    public void setMinChunkChars(int minChunkChars) {
        this.minChunkChars = minChunkChars;
    }

    public int getMinChunkTokens() {
        return minChunkTokens;
    }

    public void setMinChunkTokens(int minChunkTokens) {
        this.minChunkTokens = minChunkTokens;
    }

    public int getMaxLineCopies() {
        return maxLineCopies;
    }

    public void setMaxLineCopies(int maxLineCopies) {
        this.maxLineCopies = maxLineCopies;
    }

    public int getWarningReviewHours() {
        return warningReviewHours;
    }

    public void setWarningReviewHours(int warningReviewHours) {
        this.warningReviewHours = warningReviewHours;
    }

    public int getAiAutoApplyHours() {
        return aiAutoApplyHours;
    }

    public void setAiAutoApplyHours(int aiAutoApplyHours) {
        this.aiAutoApplyHours = aiAutoApplyHours;
    }

    public int getAdminBacklogThreshold() {
        return adminBacklogThreshold;
    }

    public void setAdminBacklogThreshold(int adminBacklogThreshold) {
        this.adminBacklogThreshold = adminBacklogThreshold;
    }

    public int getAdminBacklogAiMaxPerPass() {
        return adminBacklogAiMaxPerPass;
    }

    public void setAdminBacklogAiMaxPerPass(int adminBacklogAiMaxPerPass) {
        this.adminBacklogAiMaxPerPass = adminBacklogAiMaxPerPass;
    }

    public boolean isAiRequireHealthyMonitoring() {
        return aiRequireHealthyMonitoring;
    }

    public void setAiRequireHealthyMonitoring(boolean aiRequireHealthyMonitoring) {
        this.aiRequireHealthyMonitoring = aiRequireHealthyMonitoring;
    }

    public int getUnusedDaysThreshold() {
        return unusedDaysThreshold;
    }

    public void setUnusedDaysThreshold(int unusedDaysThreshold) {
        this.unusedDaysThreshold = unusedDaysThreshold;
    }

    public int getSnippetChars() {
        return snippetChars;
    }

    public void setSnippetChars(int snippetChars) {
        this.snippetChars = snippetChars;
    }
}
