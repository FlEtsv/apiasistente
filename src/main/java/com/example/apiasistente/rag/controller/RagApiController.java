package com.example.apiasistente.rag.controller;

import com.example.apiasistente.rag.dto.MemoryRequest;
import com.example.apiasistente.rag.dto.RagContextStatsDto;
import com.example.apiasistente.rag.dto.UpsertDocumentRequest;
import com.example.apiasistente.rag.dto.UpsertDocumentResponse;
import com.example.apiasistente.rag.service.RagIngestionService;
import com.example.apiasistente.rag.service.RagService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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

    private final RagService ragService;
    private final RagIngestionService ragIngestionService;

    public RagApiController(RagService ragService, RagIngestionService ragIngestionService) {
        this.ragService = ragService;
        this.ragIngestionService = ragIngestionService;
    }

    /**
     * Inserta o actualiza documento global.
     */
    @PostMapping("/documents")
    public UpsertDocumentResponse upsert(@Valid @RequestBody UpsertDocumentRequest req) {
        var doc = ragIngestionService.upsert(RagService.GLOBAL_OWNER, req);
        return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
    }

    /**
     * Inserta o actualiza lote de documentos globales.
     */
    @PostMapping("/documents/batch")
    public List<UpsertDocumentResponse> upsertBatch(@Valid @RequestBody List<UpsertDocumentRequest> reqs) {
        return reqs.stream()
                .map(r -> {
                    var doc = ragIngestionService.upsert(RagService.GLOBAL_OWNER, r);
                    return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
                })
                .toList();
    }

    /**
     * Inserta/actualiza documento privado para un usuario autenticado.
     */
    @PostMapping("/users/{username}/documents")
    public UpsertDocumentResponse upsertForUser(@PathVariable String username,
                                                @Valid @RequestBody UpsertDocumentRequest req,
                                                Principal principal) {
        String target = normalizePathUser(username);
        enforceSameUser(principal, target);
        var doc = ragIngestionService.upsert(target, req);
        return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
    }

    /**
     * Inserta/actualiza lote de documentos privados para usuario autenticado.
     */
    @PostMapping("/users/{username}/documents/batch")
    public List<UpsertDocumentResponse> upsertBatchForUser(@PathVariable String username,
                                                           @Valid @RequestBody List<UpsertDocumentRequest> reqs,
                                                           Principal principal) {
        String target = normalizePathUser(username);
        enforceSameUser(principal, target);
        return reqs.stream()
                .map(r -> {
                    var doc = ragIngestionService.upsert(target, r);
                    return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
                })
                .toList();
    }

    /**
     * Devuelve estadisticas de corpus visible para el usuario autenticado.
     */
    @GetMapping("/stats")
    public RagContextStatsDto stats(Principal principal) {
        String owner = (principal != null && principal.getName() != null) ? principal.getName() : RagService.GLOBAL_OWNER;
        return ragService.contextStatsForOwnerOrGlobal(owner);
    }

    /**
     * Persiste memoria de usuario.
     */
    @PostMapping("/memory")
    public UpsertDocumentResponse storeMemory(@Valid @RequestBody MemoryRequest req, Principal principal) {
        var doc = ragIngestionService.storeMemory(principal.getName(), req);
        return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
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


