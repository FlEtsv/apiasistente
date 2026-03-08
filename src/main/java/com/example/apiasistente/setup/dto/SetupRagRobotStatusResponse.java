package com.example.apiasistente.setup.dto;

/**
 * Estado simplificado del robot de mantenimiento RAG para el wizard de setup.
 */
public record SetupRagRobotStatusResponse(
        boolean poweredOn,
        boolean configuredEnabled,
        boolean paused,
        boolean running,
        String detail
) {
}
