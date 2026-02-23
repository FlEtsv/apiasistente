package com.example.apiasistente.model.dto;

import java.time.Instant;

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
