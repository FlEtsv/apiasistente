package com.example.apiasistente.monitoring.dto;

import java.time.Instant;

/**
 * DTO para Monitoring Alert.
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

