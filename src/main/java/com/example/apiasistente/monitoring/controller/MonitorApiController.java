package com.example.apiasistente.monitoring.controller;

import com.example.apiasistente.monitoring.dto.MonitoringAlertDto;
import com.example.apiasistente.monitoring.dto.MonitoringAlertStateDto;
import com.example.apiasistente.monitoring.dto.MonitoringStackStatusDto;
import com.example.apiasistente.monitoring.dto.ServerStatsDto;
import com.example.apiasistente.monitoring.service.MonitoringReadService;
import com.example.apiasistente.monitoring.service.MonitoringStackService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints de monitor para la UI autenticada de la aplicacion.
 * <p>
 * Consume {@link MonitoringReadService} para lecturas y deja en
 * {@link MonitoringStackService} las operaciones de activacion del stack Docker.
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorApiController {

    private final MonitoringReadService monitoringReadService;
    private final MonitoringStackService stackService;

    public MonitorApiController(MonitoringReadService monitoringReadService,
                                MonitoringStackService stackService) {
        this.monitoringReadService = monitoringReadService;
        this.stackService = stackService;
    }

    /**
     * Devuelve snapshot de recursos del host/JVM.
     *
     * @return metricas instantaneas de servidor
     */
    @GetMapping("/server")
    public ServerStatsDto server() {
        return monitoringReadService.serverSnapshot();
    }

    /**
     * Consulta eventos de alerta filtrados por fecha y limite.
     *
     * @param since fecha ISO-8601 opcional
     * @param limit maximo de registros
     * @return alertas recientes
     */
    @GetMapping("/alerts")
    public List<MonitoringAlertDto> alerts(@RequestParam(name = "since", required = false) String since,
                                           @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return monitoringReadService.recentAlerts(since, limit);
    }

    /**
     * Obtiene el estado agregado actual de alertas activas.
     *
     * @return estado booleano por categoria
     */
    @GetMapping("/alerts/state")
    public MonitoringAlertStateDto alertState() {
        return monitoringReadService.currentAlertState();
    }

    /**
     * Inspecciona estado operativo del stack Docker (API/Prometheus/Grafana).
     *
     * @return diagnostico actual del stack
     */
    @GetMapping("/stack/status")
    public MonitoringStackStatusDto stackStatus() {
        return stackService.status();
    }

    /**
     * Intenta levantar o completar el stack Docker de observabilidad.
     *
     * @return resultado de activacion con salida de diagnostico
     */
    @PostMapping("/stack/up")
    public MonitoringStackStatusDto stackUp() {
        return stackService.ensureUp();
    }
}
