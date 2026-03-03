package com.example.apiasistente.rag.controller;

import com.example.apiasistente.rag.dto.RagMaintenanceCaseDecisionRequest;
import com.example.apiasistente.rag.dto.RagMaintenanceCaseDto;
import com.example.apiasistente.rag.dto.RagMaintenanceConfigRequest;
import com.example.apiasistente.rag.dto.RagMaintenanceStatusDto;
import com.example.apiasistente.rag.service.RagMaintenanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * API para observar y controlar el robot de mantenimiento del RAG.
 */
@RestController
@RequestMapping("/api/rag/maintenance")
public class RagMaintenanceController {

    private final RagMaintenanceService ragMaintenanceService;

    public RagMaintenanceController(RagMaintenanceService ragMaintenanceService) {
        this.ragMaintenanceService = ragMaintenanceService;
    }

    @GetMapping("/status")
    public RagMaintenanceStatusDto status() {
        return ragMaintenanceService.status();
    }

    @GetMapping("/cases")
    public List<RagMaintenanceCaseDto> cases(@RequestParam(name = "includeResolved", defaultValue = "false") boolean includeResolved) {
        return ragMaintenanceService.listCases(includeResolved);
    }

    @PostMapping("/run")
    public RagMaintenanceStatusDto runNow() {
        return ragMaintenanceService.runManualSweep();
    }

    @PostMapping("/pause")
    public RagMaintenanceStatusDto pause() {
        return ragMaintenanceService.pause();
    }

    @PostMapping("/resume")
    public RagMaintenanceStatusDto resume() {
        return ragMaintenanceService.resume();
    }

    @PostMapping("/config")
    public RagMaintenanceStatusDto updateConfig(@RequestBody(required = false) RagMaintenanceConfigRequest request) {
        return ragMaintenanceService.updateConfig(request);
    }

    @PostMapping("/cases/{caseId}/decision")
    public RagMaintenanceCaseDto decide(@PathVariable Long caseId,
                                        @RequestBody RagMaintenanceCaseDecisionRequest request,
                                        Principal principal) {
        String actor = principal == null ? "admin" : principal.getName();
        return ragMaintenanceService.decideCase(caseId, request, actor);
    }
}
