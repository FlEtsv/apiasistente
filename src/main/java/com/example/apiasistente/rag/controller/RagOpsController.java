package com.example.apiasistente.rag.controller;

import com.example.apiasistente.rag.dto.RagOpsStatusDto;
import com.example.apiasistente.rag.service.RagOpsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API operativa del RAG.
 *
 * Responsabilidad:
 * - Dar visibilidad del pipeline real al panel principal.
 * - Exponer acciones seguras de observabilidad y reindexado manual.
 */
@RestController
@RequestMapping("/api/rag/ops")
public class RagOpsController {

    private final RagOpsService ragOpsService;

    public RagOpsController(RagOpsService ragOpsService) {
        this.ragOpsService = ragOpsService;
    }

    /**
     * Devuelve estado operativo del pipeline RAG.
     */
    @GetMapping("/status")
    public RagOpsStatusDto status() {
        return ragOpsService.status();
    }

    /**
     * Fuerza reconstruccion de indice vectorial.
     */
    @PostMapping("/index/rebuild")
    public RagOpsStatusDto rebuildIndex() {
        return ragOpsService.rebuildIndex("manual-ui");
    }

    /**
     * Limpia buffer de eventos recientes de operaciones RAG.
     */
    @PostMapping("/logs/clear")
    public RagOpsStatusDto clearLogs() {
        return ragOpsService.clearRecentEvents();
    }

    /**
     * Purga documentos antiguos para control de crecimiento del corpus.
     *
     * @param count numero de documentos a purgar
     */
    @PostMapping("/documents/purge-oldest")
    public RagOpsStatusDto purgeOldestDocuments(@RequestParam(name = "count", defaultValue = "25") int count) {
        return ragOpsService.purgeOldestDocuments(count);
    }
}
