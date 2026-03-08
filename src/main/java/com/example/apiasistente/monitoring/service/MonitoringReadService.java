package com.example.apiasistente.monitoring.service;

import com.example.apiasistente.monitoring.dto.MonitoringAlertDto;
import com.example.apiasistente.monitoring.dto.MonitoringAlertStateDto;
import com.example.apiasistente.monitoring.dto.ServerStatsDto;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fachada de lectura para endpoints de monitor.
 * <p>
 * Unifica consulta de snapshot, alertas y estado de alertas para evitar
 * duplicacion de flujo entre controladores web, externos e integracion.
 */
@Service
public class MonitoringReadService {

    private final MonitorService monitorService;
    private final MonitoringAlertStore alertStore;
    private final MonitoringAlertService alertService;

    public MonitoringReadService(MonitorService monitorService,
                                 MonitoringAlertStore alertStore,
                                 MonitoringAlertService alertService) {
        this.monitorService = monitorService;
        this.alertStore = alertStore;
        this.alertService = alertService;
    }

    /**
     * Obtiene una fotografia de recursos del host y JVM.
     *
     * @return snapshot actual del servidor
     */
    public ServerStatsDto serverSnapshot() {
        return monitorService.snapshot();
    }

    /**
     * Lista alertas recientes aplicando validacion de parametros comunes.
     *
     * @param sinceRaw fecha ISO-8601 opcional
     * @param limit maximo de eventos a retornar
     * @return lista de eventos desde el mas reciente
     */
    public List<MonitoringAlertDto> recentAlerts(String sinceRaw, int limit) {
        return alertStore.recent(MonitoringSinceParser.parseSince(sinceRaw), limit);
    }

    /**
     * Devuelve estado agregado de las alertas activas.
     *
     * @return estado booleando por categoria
     */
    public MonitoringAlertStateDto currentAlertState() {
        return alertService.currentState();
    }
}
