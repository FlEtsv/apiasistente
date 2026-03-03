package com.example.apiasistente.rag.dto;

import java.time.Instant;

/**
 * Resumen del ultimo barrido del robot de mantenimiento.
 */
public record RagMaintenanceRunDto(String trigger,
                                   String outcome,
                                   Instant startedAt,
                                   Instant completedAt,
                                   long scannedDocuments,
                                   long scannedChunks,
                                   long rebuiltDocuments,
                                   long deletedDocuments,
                                   long deletedChunks,
                                   long duplicateDocuments,
                                   long lowValueDocuments,
                                   long prunedChunks,
                                   long estimatedBytesFreed,
                                   String summary) {

    public RagMaintenanceRunDto {
        trigger = trigger == null ? "AUTO" : trigger;
        outcome = outcome == null ? "IDLE" : outcome;
        scannedDocuments = Math.max(0, scannedDocuments);
        scannedChunks = Math.max(0, scannedChunks);
        rebuiltDocuments = Math.max(0, rebuiltDocuments);
        deletedDocuments = Math.max(0, deletedDocuments);
        deletedChunks = Math.max(0, deletedChunks);
        duplicateDocuments = Math.max(0, duplicateDocuments);
        lowValueDocuments = Math.max(0, lowValueDocuments);
        prunedChunks = Math.max(0, prunedChunks);
        estimatedBytesFreed = Math.max(0, estimatedBytesFreed);
        summary = summary == null ? "" : summary;
    }

    public static RagMaintenanceRunDto empty() {
        return new RagMaintenanceRunDto(
                "AUTO",
                "IDLE",
                null,
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                "Sin barridos todavia."
        );
    }
}
