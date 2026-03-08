package com.example.apiasistente.monitoring.dto;

import java.time.Instant;

/**
 * Estado operativo del stack Docker de observabilidad.
 */
public record MonitoringStackStatusDto(
        Instant timestamp,
        boolean actionExecuted,
        boolean success,
        boolean dockerInstalled,
        boolean dockerReachable,
        boolean composeAvailable,
        String composeCommand,
        String projectDir,
        String composeFile,
        boolean composeFileFound,
        boolean apiContainerPresent,
        boolean apiContainerRunning,
        boolean prometheusContainerPresent,
        boolean prometheusContainerRunning,
        boolean grafanaContainerPresent,
        boolean grafanaContainerRunning,
        String command,
        String message,
        String output
) {
}

