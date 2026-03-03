package com.example.apiasistente.chat.dto;

import java.time.Instant;

/**
 * Evento reciente del motor de decisión RAG para la UI del dashboard.
 */
public record ChatRagTelemetryEventDto(
        Instant at,
        String type,
        String outcome,
        String reason,
        String detail
) {
}
