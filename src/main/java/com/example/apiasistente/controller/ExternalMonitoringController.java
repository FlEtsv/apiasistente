package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.MonitoringAlertDto;
import com.example.apiasistente.model.dto.MonitoringAlertStateDto;
import com.example.apiasistente.model.dto.ServerStatsDto;
import com.example.apiasistente.service.MonitorService;
import com.example.apiasistente.service.MonitoringAlertService;
import com.example.apiasistente.service.MonitoringAlertStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

@RestController
@RequestMapping("/api/ext/monitor")
public class ExternalMonitoringController {

    private final MonitorService monitorService;
    private final MonitoringAlertService alertService;
    private final MonitoringAlertStore alertStore;

    public ExternalMonitoringController(
            MonitorService monitorService,
            MonitoringAlertService alertService,
            MonitoringAlertStore alertStore
    ) {
        this.monitorService = monitorService;
        this.alertService = alertService;
        this.alertStore = alertStore;
    }

    @GetMapping("/server")
    public ServerStatsDto server() {
        return monitorService.snapshot();
    }

    @GetMapping("/alerts")
    public List<MonitoringAlertDto> alerts(
            @RequestParam(name = "since", required = false) String since,
            @RequestParam(name = "limit", defaultValue = "50") int limit
    ) {
        return alertStore.recent(parseSince(since), limit);
    }

    @GetMapping("/alerts/state")
    public MonitoringAlertStateDto alertState() {
        return alertService.currentState();
    }

    private Instant parseSince(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parametro 'since' invalido. Usa ISO-8601.");
        }
    }
}
