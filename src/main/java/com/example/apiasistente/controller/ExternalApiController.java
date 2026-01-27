// ExternalApiController.java
package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.ChatRequest;
import com.example.apiasistente.model.dto.ChatResponse;
import com.example.apiasistente.model.dto.MemoryRequest;
import com.example.apiasistente.model.dto.UpsertDocumentRequest;
import com.example.apiasistente.model.dto.UpsertDocumentResponse;
import com.example.apiasistente.service.ChatService;
import com.example.apiasistente.service.RagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ext")
public class ExternalApiController {

    private final ChatService chatService;
    private final RagService ragService;

    public ExternalApiController(ChatService chatService, RagService ragService) {
        this.chatService = chatService;
        this.ragService = ragService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest req, Principal principal) {
        return chatService.chat(principal.getName(), req.getSessionId(), req.getMessage());
    }

    @PostMapping("/rag/documents")
    public UpsertDocumentResponse upsert(@Valid @RequestBody UpsertDocumentRequest req) {
        var doc = ragService.upsertDocument(req.getTitle(), req.getContent());
        return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
    }

    @PostMapping("/rag/documents/batch")
    public List<UpsertDocumentResponse> upsertBatch(@Valid @RequestBody List<UpsertDocumentRequest> reqs) {
        return reqs.stream().map(r -> {
            var doc = ragService.upsertDocument(r.getTitle(), r.getContent());
            return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
        }).toList();
    }

    @PostMapping("/rag/memory")
    public UpsertDocumentResponse storeMemory(@Valid @RequestBody MemoryRequest req, Principal principal) {
        var doc = ragService.storeMemory(principal.getName(), req.getTitle(), req.getContent());
        return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
    }

}
