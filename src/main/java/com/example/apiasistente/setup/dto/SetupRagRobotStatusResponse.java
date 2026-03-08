package com.example.apiasistente.setup.dto;

/**
 * Estado simplificado del robot de mantenimiento RAG para el wizard de setup.
 *
 * @param poweredOn estado efectivo de encendido para UI
 * @param configuredEnabled si la feature esta habilitada en configuracion base
 * @param paused si esta pausado manualmente
 * @param running si hay un barrido en curso
 * @param detail texto explicativo para usuario
 */
public record SetupRagRobotStatusResponse(
        boolean poweredOn,
        boolean configuredEnabled,
        boolean paused,
        boolean running,
        String detail
) {
}
