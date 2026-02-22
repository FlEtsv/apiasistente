package com.example.apiasistente.model.dto;

import java.time.Instant;

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
