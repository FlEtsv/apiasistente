package com.example.apiasistente.monitoring.dto;

import java.time.Instant;

/**
 * Evento de alerta o recuperacion generado por monitor.
 *
 * @param id identificador unico del evento
 * @param timestamp instante de emision
 * @param level nivel del evento (ALERT/RECOVER)
 * @param key clave tecnica de la alerta
 * @param title titulo corto legible
 * @param message detalle multilinea del evento
 * @param hostname host evaluado
 * @param value valor observado (si aplica)
 * @param threshold umbral configurado (si aplica)
 * @param latencyMs latencia de red observada (solo alertas de internet)
 * @param checkedUrl endpoint comprobado para internet
 */
public record MonitoringAlertDto(
        String id,
        Instant timestamp,
        String level,
        String key,
        String title,
        String message,
        String hostname,
        Double value,
        Double threshold,
        Long latencyMs,
        String checkedUrl
) {}

