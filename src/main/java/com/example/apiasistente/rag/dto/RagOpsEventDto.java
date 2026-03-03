package com.example.apiasistente.rag.dto;

import java.time.Instant;

/**
 * Evento operativo reciente del subsistema RAG.
 *
 * Responsabilidad:
 * - Exponer a la web y a tests una traza compacta de lo que ha hecho el pipeline.
 * - Mantener separados los logs de infraestructura del estado funcional del RAG.
 */
public record RagOpsEventDto(
        Instant at,
        String level,
        String type,
        String summary,
        String detail
) {
}
