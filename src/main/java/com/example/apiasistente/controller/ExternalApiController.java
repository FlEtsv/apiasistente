// ExternalApiController.java
package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.ChatRequest;
import com.example.apiasistente.model.dto.ChatResponse;
import com.example.apiasistente.model.dto.UpsertDocumentRequest;
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
    public Map<String, String> upsert(@Valid @RequestBody UpsertDocumentRequest req) {
        var doc = ragService.upsertDocument(req.getTitle(), req.getContent());
        return Map.of("documentId", doc.getId(), "title", doc.getTitle());
    }

    @PostMapping("/rag/documents/batch")
    public List<Map<String, String>> upsertBatch(@Valid @RequestBody List<UpsertDocumentRequest> reqs) {
        return reqs.stream().map(r -> {
            var doc = ragService.upsertDocument(r.getTitle(), r.getContent());
            return Map.of("documentId", doc.getId(), "title", doc.getTitle());
        }).toList();
    }
}
