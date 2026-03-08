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
 * API REST del wizard de instalacion inicial.
 * <p>
 * Expone estado/configuracion del setup y acciones operativas guiadas
 * (scraper manual y encendido/apagado del robot de mantenimiento RAG).
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

    /**
     * Devuelve si la instalacion ya fue configurada.
     *
     * @return estado global del setup
     */
    @GetMapping("/status")
    public SetupStatusResponse status() {
        return new SetupStatusResponse(setupConfigService.isConfigured());
    }

    /**
     * Retorna la configuracion actualmente persistida.
     *
     * @return snapshot del setup actual
     */
    @GetMapping("/config")
    public SetupConfigResponse config() {
        return setupConfigService.current();
    }

    /**
     * Retorna el preset base recomendado por el despliegue.
     *
     * @return defaults de setup sin leer base de datos
     */
    @GetMapping("/defaults")
    public SetupConfigResponse defaults() {
        return setupConfigService.defaults();
    }

    /**
     * Guarda la configuracion del setup y la deja marcada como completada.
     *
     * @param request payload de configuracion inicial
     * @return configuracion normalizada/persistida
     */
    @PutMapping("/config")
    public SetupConfigResponse save(@Valid @RequestBody SetupConfigRequest request) {
        return setupConfigService.save(request);
    }

    /**
     * Ejecuta una corrida manual del scraper web para validar integracion RAG.
     *
     * @return resumen operativo del scraping
     */
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

    /**
     * Devuelve estado simplificado del robot de mantenimiento RAG.
     *
     * @return estado de scheduler/pausa/ejecucion para UI de setup
     */
    @GetMapping("/rag-robot/status")
    public SetupRagRobotStatusResponse ragRobotStatus() {
        return toRagRobotStatus(ragMaintenanceService.status());
    }

    /**
     * Enciende o apaga el robot de mantenimiento RAG.
     *
     * @param enabled {@code true} para reanudar, {@code false} para pausar
     * @return estado posterior al cambio de energia
     */
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
