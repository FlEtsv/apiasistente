// ExternalApiController.java
package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.ChatRequest;
import com.example.apiasistente.model.dto.ChatResponse;
import com.example.apiasistente.model.dto.MemoryRequest;
import com.example.apiasistente.model.dto.UpsertDocumentRequest;
import com.example.apiasistente.model.dto.UpsertDocumentResponse;
import com.example.apiasistente.service.ChatQueueService;
import com.example.apiasistente.service.RagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ext")
public class ExternalApiController {

    private final ChatQueueService chatQueueService;
    private final RagService ragService;

    public ExternalApiController(ChatQueueService chatQueueService, RagService ragService) {
        this.chatQueueService = chatQueueService;
        this.ragService = ragService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest req, Principal principal) {
        return chatQueueService.chatAndWait(
                principal.getName(),
                req.getSessionId(),
                req.getMessage(),
                req.getModel()
        );
    }

    @PostMapping("/rag/documents")
    public UpsertDocumentResponse upsert(@Valid @RequestBody UpsertDocumentRequest req, Principal principal) {
        var doc = ragService.upsertDocumentForOwner(principal.getName(), req.getTitle(), req.getContent());
        return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
    }

    @PostMapping("/rag/documents/batch")
    public List<UpsertDocumentResponse> upsertBatch(@Valid @RequestBody List<UpsertDocumentRequest> reqs, Principal principal) {
        String owner = principal.getName();
        return reqs.stream().map(r -> {
            var doc = ragService.upsertDocumentForOwner(owner, r.getTitle(), r.getContent());
            return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
        }).toList();
    }


    @PostMapping("/rag/memory")
    public UpsertDocumentResponse storeMemory(@Valid @RequestBody MemoryRequest req, Principal principal) {
        var doc = ragService.storeMemory(principal.getName(), req.getTitle(), req.getContent());
        return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
    }
}
