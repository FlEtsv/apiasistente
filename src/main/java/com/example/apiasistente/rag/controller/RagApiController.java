package com.example.apiasistente.rag.controller;

import com.example.apiasistente.rag.dto.MemoryRequest;
import com.example.apiasistente.rag.dto.RagContextStatsDto;
import com.example.apiasistente.rag.dto.UpsertDocumentRequest;
import com.example.apiasistente.rag.dto.UpsertDocumentResponse;
import com.example.apiasistente.rag.service.RagService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.security.Principal;

/**
 * API interna de ingesta y consulta del contexto RAG.
 *
 * Responsabilidad:
 * - Traducir el contrato HTTP al core de `RagService`.
 * - Mantener compatibilidad con payload legacy mientras el core trabaja con la estructura nueva.
 */
@RestController
@RequestMapping("/api/rag")
public class RagApiController {

    private static final Logger log = LoggerFactory.getLogger(RagApiController.class);

    private final RagService ragService;

    public RagApiController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/documents")
    public UpsertDocumentResponse upsert(@Valid @RequestBody UpsertDocumentRequest req) {
        var doc = upsertWithOptionalMetadata(RagService.GLOBAL_OWNER, req);
        return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
    }

    @PostMapping("/documents/batch")
    public List<UpsertDocumentResponse> upsertBatch(@Valid @RequestBody List<UpsertDocumentRequest> reqs) {
        List<UpsertDocumentResponse> results = new ArrayList<>(reqs.size());
        for (var r : reqs) {
            try {
                var doc = upsertWithOptionalMetadata(RagService.GLOBAL_OWNER, r);
                results.add(new UpsertDocumentResponse(doc.getId(), doc.getTitle()));
            } catch (Exception e) {
                log.warn("rag_batch_item_fail title='{}' cause={}", r.getTitle(), e.getMessage());
            }
        }
        return results;
    }

    @PostMapping("/users/{username}/documents")
    public UpsertDocumentResponse upsertForUser(@PathVariable String username,
                                                @Valid @RequestBody UpsertDocumentRequest req,
                                                Principal principal) {
        String target = normalizePathUser(username);
        enforceSameUser(principal, target);
        var doc = upsertWithOptionalMetadata(target, req);
        return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
    }

    @PostMapping("/users/{username}/documents/batch")
    public List<UpsertDocumentResponse> upsertBatchForUser(@PathVariable String username,
                                                           @Valid @RequestBody List<UpsertDocumentRequest> reqs,
                                                           Principal principal) {
        String target = normalizePathUser(username);
        enforceSameUser(principal, target);
        List<UpsertDocumentResponse> results = new ArrayList<>(reqs.size());
        for (var r : reqs) {
            try {
                var doc = upsertWithOptionalMetadata(target, r);
                results.add(new UpsertDocumentResponse(doc.getId(), doc.getTitle()));
            } catch (Exception e) {
                log.warn("rag_batch_user_item_fail user='{}' title='{}' cause={}", target, r.getTitle(), e.getMessage());
            }
        }
        return results;
    }

    @GetMapping("/stats")
    public RagContextStatsDto stats(Principal principal) {
        String owner = (principal != null && principal.getName() != null) ? principal.getName() : RagService.GLOBAL_OWNER;
        return ragService.contextStatsForOwnerOrGlobal(owner);
    }

    @PostMapping("/memory")
    public UpsertDocumentResponse storeMemory(@Valid @RequestBody MemoryRequest req, Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario autenticado requerido.");
        }
        var doc = storeMemoryWithOptionalMetadata(principal.getName(), req);
        return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
    }

    private com.example.apiasistente.rag.entity.KnowledgeDocument upsertWithOptionalMetadata(String owner,
                                                                                              UpsertDocumentRequest req) {
        // Siempre usa el camino estructurado para que los docs manuales tengan la misma
        // metadata que los del scraper: source visible, tags indexables por la gate probe.
        // Si el cliente no manda source, usamos "manual" en lugar de "api" para que sea
        // legible en el panel y no quede como un campo técnico sin significado.
        String source = (req.getSource() != null && !req.getSource().isBlank())
                ? req.getSource()
                : "manual";
        return ragService.upsertStructuredDocumentForOwner(
                owner,
                req.getTitle(),
                req.getContent(),
                source,
                req.getTags(),
                req.getUrl(),
                mapChunks(req)
        );
    }

    private com.example.apiasistente.rag.entity.KnowledgeDocument storeMemoryWithOptionalMetadata(String owner,
                                                                                                  MemoryRequest req) {
        if ((req.getSource() == null || req.getSource().isBlank()) && (req.getTags() == null || req.getTags().isBlank())) {
            return ragService.storeMemory(owner, req.getTitle(), req.getContent());
        }
        String source = req.getSource() == null || req.getSource().isBlank() ? "memory" : req.getSource();
        return ragService.upsertDocumentForOwner(owner, req.getTitle(), req.getContent(), source, req.getTags());
    }

    /**
     * Traduce el payload HTTP al formato interno del servicio sin mezclar DTOs web dentro del core.
     */
    private List<RagService.IncomingChunk> mapChunks(UpsertDocumentRequest req) {
        if (req.getChunks() == null || req.getChunks().isEmpty()) {
            return List.of();
        }
        return req.getChunks().stream()
                .filter(chunk -> chunk != null)
                .map(chunk -> new RagService.IncomingChunk(
                        chunk.getChunkIndex(),
                        chunk.getText(),
                        chunk.getHash(),
                        chunk.getTokenCount(),
                        chunk.getSource(),
                        chunk.getTags()
                ))
                .toList();
    }

    private void enforceSameUser(Principal principal, String targetUsername) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario autenticado requerido.");
        }

        if (!principal.getName().equalsIgnoreCase(targetUsername)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Solo puedes cargar RAG individual para tu propio usuario."
            );
        }
    }

    private String normalizePathUser(String raw) {
        String clean = (raw == null) ? "" : raw.trim();
        if (clean.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username es obligatorio.");
        }
        if (clean.length() > 120) {
            clean = clean.substring(0, 120);
        }
        return clean;
    }
}


