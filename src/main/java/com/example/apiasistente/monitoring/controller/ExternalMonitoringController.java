package com.example.apiasistente.monitoring.controller;

import com.example.apiasistente.monitoring.dto.MonitoringAlertDto;
import com.example.apiasistente.monitoring.dto.MonitoringAlertStateDto;
import com.example.apiasistente.monitoring.dto.ServerStatsDto;
import com.example.apiasistente.monitoring.service.MonitoringReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoints de monitor para clientes externos autenticados por API key.
 */
@RestController
@RequestMapping("/api/ext/monitor")
public class ExternalMonitoringController {

    private final MonitoringReadService monitoringReadService;

    public ExternalMonitoringController(MonitoringReadService monitoringReadService) {
        this.monitoringReadService = monitoringReadService;
    }

    /**
     * Obtiene snapshot de servidor para integraciones externas.
     *
     * @return estadisticas de host/JVM
     */
    @GetMapping("/server")
    public ServerStatsDto server() {
        return monitoringReadService.serverSnapshot();
    }

    /**
     * Lista alertas recientes para cliente externo.
     *
     * @param since fecha ISO-8601 opcional
     * @param limit maximo de eventos
     * @return eventos de alerta
     */
    @GetMapping("/alerts")
    public List<MonitoringAlertDto> alerts(
            @RequestParam(name = "since", required = false) String since,
            @RequestParam(name = "limit", defaultValue = "50") int limit
    ) {
        return monitoringReadService.recentAlerts(since, limit);
    }

    /**
     * Retorna estado agregado de alertas.
     *
     * @return estado activo por tipo de alerta
     */
    @GetMapping("/alerts/state")
    public MonitoringAlertStateDto alertState() {
        return monitoringReadService.currentAlertState();
    }
}


