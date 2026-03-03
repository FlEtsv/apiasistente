package com.example.apiasistente.monitoring.dto;

import java.time.Instant;

/**
 * DTO para Monitoring Alert State.
 */
public record MonitoringAlertStateDto(
        boolean cpuHigh,
        boolean memoryHigh,
        boolean diskHigh,
        boolean swapHigh,
        boolean internetDown,
        Instant cpuLastChange,
        Instant memoryLastChange,
        Instant diskLastChange,
        Instant swapLastChange,
        Instant internetLastChange
) {}

