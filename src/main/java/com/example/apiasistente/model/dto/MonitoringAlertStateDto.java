package com.example.apiasistente.model.dto;

import java.time.Instant;

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
