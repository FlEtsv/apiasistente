package com.example.apiasistente.rag.dto;

import java.time.Instant;

/**
 * Evento reciente emitido por el robot de mantenimiento.
 */
public record RagMaintenanceEventDto(Instant timestamp,
                                     String level,
                                     String type,
                                     String title,
                                     String message) {

    public RagMaintenanceEventDto {
        level = level == null ? "INFO" : level;
        type = type == null ? "EVENT" : type;
        title = title == null ? "" : title;
        message = message == null ? "" : message;
    }
}
