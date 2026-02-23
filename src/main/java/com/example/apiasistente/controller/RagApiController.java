package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.MemoryRequest;
import com.example.apiasistente.model.dto.RagContextStatsDto;
import com.example.apiasistente.model.dto.UpsertDocumentRequest;
import com.example.apiasistente.model.dto.UpsertDocumentResponse;
import com.example.apiasistente.service.RagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

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
}
