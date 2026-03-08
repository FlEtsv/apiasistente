package com.example.apiasistente.monitoring.dto;

import java.time.Instant;

/**
 * Diagnostico operativo del stack Docker de observabilidad.
 *
 * @param timestamp instante de medicion
 * @param actionExecuted si hubo intento de accion (stack up)
 * @param success resultado de la accion solicitada
 * @param dockerInstalled disponibilidad de CLI Docker
 * @param dockerReachable conectividad con daemon Docker
 * @param composeAvailable disponibilidad de Docker Compose
 * @param composeCommand comando compose resuelto
 * @param projectDir directorio de trabajo usado para comandos
 * @param composeFile ruta resuelta del compose file
 * @param composeFileFound si existe el compose file
 * @param apiContainerPresent si API existe o es detectable
 * @param apiContainerRunning si API esta corriendo
 * @param prometheusContainerPresent si Prometheus existe o es detectable
 * @param prometheusContainerRunning si Prometheus esta corriendo
 * @param grafanaContainerPresent si Grafana existe o es detectable
 * @param grafanaContainerRunning si Grafana esta corriendo
 * @param command ultimo comando ejecutado para accionar stack
 * @param message resumen legible de estado
 * @param output salida diagnostica truncada de comandos
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
