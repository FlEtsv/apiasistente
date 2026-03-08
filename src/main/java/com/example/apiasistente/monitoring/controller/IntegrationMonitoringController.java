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
 * Endpoints de monitor para integracion entre aplicaciones.
 * <p>
 * Reutiliza la misma fachada de lectura que el resto de superficies
 * para asegurar un comportamiento consistente.
 */
@RestController
@RequestMapping("/api/integration/monitor")
public class IntegrationMonitoringController {

    private final MonitoringReadService monitoringReadService;

    public IntegrationMonitoringController(MonitoringReadService monitoringReadService) {
        this.monitoringReadService = monitoringReadService;
    }

    /**
     * Devuelve snapshot de servidor para canal de integracion.
     *
     * @return estado instantaneo del servidor
     */
    @GetMapping("/server")
    public ServerStatsDto server() {
        return monitoringReadService.serverSnapshot();
    }

    /**
     * Devuelve eventos de alerta filtrados para integracion.
     *
     * @param since fecha ISO-8601 opcional
     * @param limit maximo de eventos
     * @return lista de alertas recientes
     */
    @GetMapping("/alerts")
    public List<MonitoringAlertDto> alerts(
            @RequestParam(name = "since", required = false) String since,
            @RequestParam(name = "limit", defaultValue = "50") int limit
    ) {
        return monitoringReadService.recentAlerts(since, limit);
    }

    /**
     * Devuelve el estado agregado de alertas para paneles de integracion.
     *
     * @return estado actual por tipo
     */
    @GetMapping("/alerts/state")
    public MonitoringAlertStateDto alertState() {
        return monitoringReadService.currentAlertState();
    }
}


