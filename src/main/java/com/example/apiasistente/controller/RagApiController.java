package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.UpsertDocumentRequest;
import com.example.apiasistente.service.RagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rag")
public class RagApiController {

    private final RagService ragService;

    public RagApiController(RagService ragService) {
        this.ragService = ragService;
    }

    /**
     * Sube/actualiza un documento RAG (vía web, con sesión + CSRF).
     * Devuelve el id del documento y su título.
     */
    @PostMapping("/documents")
    public UpsertDocumentResponse upsert(@Valid @RequestBody UpsertDocumentRequest req) {
        var doc = ragService.upsertDocument(req.getTitle(), req.getContent());
        return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
    }

    /**
     * Sube varios documentos de golpe.
     * Útil para “trocear” un texto grande o para subir múltiples ficheros.
     */
    @PostMapping("/documents/batch")
    public List<UpsertDocumentResponse> upsertBatch(@Valid @RequestBody List<UpsertDocumentRequest> reqs) {
        return reqs.stream()
                .map(r -> {
                    var doc = ragService.upsertDocument(r.getTitle(), r.getContent());
                    return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
                })
                .toList();
    }

    public record UpsertDocumentResponse(Long documentId, String title) {}
}
