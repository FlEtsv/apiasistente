package com.example.apiasistente.rag.dto;

import java.time.Instant;

/**
 * Snapshot minimo del contexto utilizable por chat.
 *
 * Responsabilidad:
 * - Responder rapido cuanta base activa puede consultar una sesion.
 * - Mostrar la configuracion esencial del retrieval sin exponer detalles internos del core.
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

