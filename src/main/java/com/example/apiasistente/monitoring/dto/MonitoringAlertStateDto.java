package com.example.apiasistente.monitoring.dto;

import java.time.Instant;

/**
 * Estado agregado de alertas activas.
 *
 * @param cpuHigh alerta CPU activa
 * @param memoryHigh alerta memoria activa
 * @param diskHigh alerta disco activa
 * @param swapHigh alerta swap activa
 * @param internetDown alerta de conectividad activa
 * @param cpuLastChange ultima transicion de estado CPU
 * @param memoryLastChange ultima transicion de estado memoria
 * @param diskLastChange ultima transicion de estado disco
 * @param swapLastChange ultima transicion de estado swap
 * @param internetLastChange ultima transicion de conectividad
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

