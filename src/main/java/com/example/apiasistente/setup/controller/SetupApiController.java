package com.example.apiasistente.setup.controller;

import com.example.apiasistente.rag.service.RagWebScraperService;
import com.example.apiasistente.setup.dto.SetupConfigRequest;
import com.example.apiasistente.setup.dto.SetupConfigResponse;
import com.example.apiasistente.setup.dto.SetupRagRobotStatusResponse;
import com.example.apiasistente.setup.dto.SetupScraperRunResponse;
import com.example.apiasistente.setup.dto.SetupStatusResponse;
import com.example.apiasistente.setup.service.SetupConfigService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.example.apiasistente.rag.dto.RagMaintenanceStatusDto;
import com.example.apiasistente.rag.service.RagMaintenanceService;

/**
 * API del wizard de instalacion.
 */
@RestController
@RequestMapping("/api/setup")
public class SetupApiController {

    private final SetupConfigService setupConfigService;
    private final RagWebScraperService ragWebScraperService;
    private final RagMaintenanceService ragMaintenanceService;

    public SetupApiController(SetupConfigService setupConfigService,
                              RagWebScraperService ragWebScraperService,
                              RagMaintenanceService ragMaintenanceService) {
        this.setupConfigService = setupConfigService;
        this.ragWebScraperService = ragWebScraperService;
        this.ragMaintenanceService = ragMaintenanceService;
    }

    @GetMapping("/status")
    public SetupStatusResponse status() {
        return new SetupStatusResponse(setupConfigService.isConfigured());
    }

    @GetMapping("/config")
    public SetupConfigResponse config() {
        return setupConfigService.current();
    }

    @GetMapping("/defaults")
    public SetupConfigResponse defaults() {
        return setupConfigService.defaults();
    }

    @PutMapping("/config")
    public SetupConfigResponse save(@Valid @RequestBody SetupConfigRequest request) {
        return setupConfigService.save(request);
    }

    @PostMapping("/scraper/run")
    public SetupScraperRunResponse runScraperNow() {
        RagWebScraperService.ScrapeRunResult result = ragWebScraperService.scrapeNow();
        return new SetupScraperRunResponse(
                result.executed(),
                result.processed(),
                result.updated(),
                result.skipped(),
                result.failed(),
                result.message()
        );
    }

    @GetMapping("/rag-robot/status")
    public SetupRagRobotStatusResponse ragRobotStatus() {
        return toRagRobotStatus(ragMaintenanceService.status());
    }

    @PostMapping("/rag-robot/power")
    public SetupRagRobotStatusResponse ragRobotPower(@RequestParam boolean enabled) {
        RagMaintenanceStatusDto status = enabled
                ? ragMaintenanceService.resume()
                : ragMaintenanceService.pause();
        return toRagRobotStatus(status);
    }

    private SetupRagRobotStatusResponse toRagRobotStatus(RagMaintenanceStatusDto status) {
        boolean configuredEnabled = status != null && status.schedulerEnabled();
        boolean paused = status != null && status.paused();
        boolean running = status != null && status.running();
        boolean poweredOn = configuredEnabled && !paused;

        String detail;
        if (!configuredEnabled) {
            detail = "Deshabilitado por configuracion base (rag.maintenance.enabled=false).";
        } else if (paused) {
            detail = "Pausado manualmente.";
        } else if (running) {
            detail = "Activo y ejecutando barrido.";
        } else {
            detail = "Activo y en espera del siguiente ciclo.";
        }

        return new SetupRagRobotStatusResponse(poweredOn, configuredEnabled, paused, running, detail);
    }
}
