package com.example.apiasistente.rag.dto;

import java.time.Instant;

/**
 * DTO para RAG Context Stats.
 */
public record RagContextStatsDto(
        String owner,
        long totalDocuments,
        long totalChunks,
        long globalDocuments,
        long globalChunks,
        long ownerDocuments,
        long ownerChunks,
        Instant lastUpdatedAt,
        int topK,
        int chunkSize,
        int chunkOverlap
) {
}

