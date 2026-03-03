package com.example.apiasistente.rag.dto;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot operativo del RAG.
 *
 * Responsabilidad:
 * - Resumir arquitectura, volumen, configuracion y actividad reciente.
 * - Servir como contrato unico para la consola web de observabilidad RAG.
 */
public record RagOpsStatusDto(
        String architecture,
        String indexLocation,
        Instant updatedAt,
        Instant lastCorpusUpdateAt,
        long activeDocuments,
        long activeChunks,
        long activeVectors,
        long metadataBytes,
        long chunkTextBytes,
        long embeddingBytes,
        long indexBytes,
        long totalBytes,
        int topK,
        int chunkSize,
        int chunkOverlap,
        int contextMaxChunks,
        int rerankCandidates,
        double evidenceThreshold,
        long ingestOperations,
        long retrievalOperations,
        long deletedDocuments,
        long prunedChunks,
        long indexWrites,
        long indexDeletes,
        long indexRebuilds,
        long failures,
        Instant lastIngestAt,
        String lastIngestSummary,
        Instant lastRetrievalAt,
        String lastRetrievalSummary,
        Instant lastDeleteAt,
        String lastDeleteSummary,
        Instant lastIndexAt,
        String lastIndexSummary,
        List<RagOpsEventDto> recentEvents
) {
    public RagOpsStatusDto {
        recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
    }
}
