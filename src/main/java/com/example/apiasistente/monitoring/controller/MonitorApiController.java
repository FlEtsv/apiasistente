package com.example.apiasistente.monitoring.controller;

import com.example.apiasistente.monitoring.dto.MonitoringAlertDto;
import com.example.apiasistente.monitoring.dto.MonitoringAlertStateDto;
import com.example.apiasistente.monitoring.dto.ServerStatsDto;
import com.example.apiasistente.monitoring.service.MonitorService;
import com.example.apiasistente.monitoring.service.MonitoringAlertService;
import com.example.apiasistente.monitoring.service.MonitoringAlertStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controlador para Monitor API.
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorApiController {

    private final MonitorService monitorService;
    private final MonitoringAlertStore alertStore;
    private final MonitoringAlertService alertService;

    public MonitorApiController(MonitorService monitorService,
                                MonitoringAlertStore alertStore,
                                MonitoringAlertService alertService) {
        this.monitorService = monitorService;
        this.alertStore = alertStore;
        this.alertService = alertService;
    }

    @GetMapping("/server")
    public ServerStatsDto server() {
        return monitorService.snapshot();
    }

    @GetMapping("/alerts")
    public List<MonitoringAlertDto> alerts(@RequestParam(name = "since", required = false) String since,
                                           @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return alertStore.recent(MonitoringRequestParser.parseSince(since), limit);
    }

    @GetMapping("/alerts/state")
    public MonitoringAlertStateDto alertState() {
        return alertService.currentState();
    }
}
