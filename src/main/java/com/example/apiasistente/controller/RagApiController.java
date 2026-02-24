package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.MemoryRequest;
import com.example.apiasistente.model.dto.RagContextStatsDto;
import com.example.apiasistente.model.dto.UpsertDocumentRequest;
import com.example.apiasistente.model.dto.UpsertDocumentResponse;
import com.example.apiasistente.service.RagService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.security.Principal;

@RestController
@RequestMapping("/api/rag")
public class RagApiController {

    private final RagService ragService;

    public RagApiController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/documents")
    public UpsertDocumentResponse upsert(@Valid @RequestBody UpsertDocumentRequest req) {
        var doc = ragService.upsertDocument(req.getTitle(), req.getContent());
        return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
    }

    @PostMapping("/documents/batch")
    public List<UpsertDocumentResponse> upsertBatch(@Valid @RequestBody List<UpsertDocumentRequest> reqs) {
        return reqs.stream()
                .map(r -> {
                    var doc = ragService.upsertDocument(r.getTitle(), r.getContent());
                    return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
                })
                .toList();
    }

    @PostMapping("/users/{username}/documents")
    public UpsertDocumentResponse upsertForUser(@PathVariable String username,
                                                @Valid @RequestBody UpsertDocumentRequest req,
                                                Principal principal) {
        String target = normalizePathUser(username);
        enforceSameUser(principal, target);
        var doc = ragService.upsertDocumentForOwner(target, req.getTitle(), req.getContent());
        return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
    }

    @PostMapping("/users/{username}/documents/batch")
    public List<UpsertDocumentResponse> upsertBatchForUser(@PathVariable String username,
                                                           @Valid @RequestBody List<UpsertDocumentRequest> reqs,
                                                           Principal principal) {
        String target = normalizePathUser(username);
        enforceSameUser(principal, target);
        return reqs.stream()
                .map(r -> {
                    var doc = ragService.upsertDocumentForOwner(target, r.getTitle(), r.getContent());
                    return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
                })
                .toList();
    }

    @GetMapping("/stats")
    public RagContextStatsDto stats(Principal principal) {
        String owner = (principal != null && principal.getName() != null) ? principal.getName() : RagService.GLOBAL_OWNER;
        return ragService.contextStatsForOwnerOrGlobal(owner);
    }

    @PostMapping("/memory")
    public UpsertDocumentResponse storeMemory(@Valid @RequestBody MemoryRequest req, Principal principal) {
        var doc = ragService.storeMemory(principal.getName(), req.getTitle(), req.getContent());
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
