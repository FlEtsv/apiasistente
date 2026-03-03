package com.example.apiasistente.rag.dto;

/**
 * Ajustes runtime del robot de mantenimiento.
 */
public class RagMaintenanceConfigRequest {

    private Boolean dryRun;
    private Integer intervalSeconds;

    public Boolean getDryRun() {
        return dryRun;
    }

    public void setDryRun(Boolean dryRun) {
        this.dryRun = dryRun;
    }

    public Integer getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(Integer intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }
}
