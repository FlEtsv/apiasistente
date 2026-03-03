package com.example.apiasistente.rag.dto;

import java.time.Instant;
import java.util.List;

/**
 * Estado visible del robot de mantenimiento del RAG.
 */
public record RagMaintenanceStatusDto(boolean schedulerEnabled,
                                      boolean paused,
                                      boolean dryRun,
                                      boolean running,
                                      long intervalMs,
                                      Instant lastStartedAt,
                                      Instant lastCompletedAt,
                                      Instant nextRunAt,
                                      String currentStep,
                                      String currentDocumentTitle,
                                      RagMaintenanceCorpusDto corpus,
                                      RagMaintenanceRunDto lastRun,
                                      List<RagMaintenanceEventDto> recentEvents) {

    public RagMaintenanceStatusDto {
        intervalMs = Math.max(0, intervalMs);
        currentStep = currentStep == null ? "" : currentStep;
        currentDocumentTitle = currentDocumentTitle == null ? "" : currentDocumentTitle;
        corpus = corpus == null ? RagMaintenanceCorpusDto.empty() : corpus;
        lastRun = lastRun == null ? RagMaintenanceRunDto.empty() : lastRun;
        recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
    }
}
