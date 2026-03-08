package com.example.apiasistente.monitoring.controller;

import com.example.apiasistente.monitoring.dto.MonitoringAlertDto;
import com.example.apiasistente.monitoring.dto.MonitoringAlertStateDto;
import com.example.apiasistente.monitoring.dto.MonitoringStackStatusDto;
import com.example.apiasistente.monitoring.dto.ServerStatsDto;
import com.example.apiasistente.monitoring.service.MonitorService;
import com.example.apiasistente.monitoring.service.MonitoringAlertService;
import com.example.apiasistente.monitoring.service.MonitoringAlertStore;
import com.example.apiasistente.monitoring.service.MonitoringStackService;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final MonitoringStackService stackService;

    public MonitorApiController(MonitorService monitorService,
                                MonitoringAlertStore alertStore,
                                MonitoringAlertService alertService,
                                MonitoringStackService stackService) {
        this.monitorService = monitorService;
        this.alertStore = alertStore;
        this.alertService = alertService;
        this.stackService = stackService;
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

    @GetMapping("/stack/status")
    public MonitoringStackStatusDto stackStatus() {
        return stackService.status();
    }

    @PostMapping("/stack/up")
    public MonitoringStackStatusDto stackUp() {
        return stackService.ensureUp();
    }
}
