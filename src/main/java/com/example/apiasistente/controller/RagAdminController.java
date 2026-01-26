package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.UpsertDocumentRequest;
import com.example.apiasistente.service.RagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rag")
public class RagAdminController {

    private final RagService ragService;

    public RagAdminController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/documents")
    public Object upsert(@Valid @RequestBody UpsertDocumentRequest req) {
        var doc = ragService.upsertDocument(req.getTitle(), req.getContent());
        return java.util.Map.of("documentId", doc.getId(), "title", doc.getTitle());
    }
    @PostMapping("/documents/batch")
    public Object upsertBatch(@Valid @RequestBody java.util.List<UpsertDocumentRequest> reqs) {
        return reqs.stream().map(req -> {
            var doc = ragService.upsertDocument(req.getTitle(), req.getContent());
            return java.util.Map.of("documentId", doc.getId(), "title", doc.getTitle());
        }).toList();
    }

}
