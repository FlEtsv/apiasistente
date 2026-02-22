// ExternalApiController.java
package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.ChatRequest;
import com.example.apiasistente.model.dto.ChatResponse;
import com.example.apiasistente.model.dto.MemoryRequest;
import com.example.apiasistente.model.dto.UpsertDocumentRequest;
import com.example.apiasistente.model.dto.UpsertDocumentResponse;
import com.example.apiasistente.security.ApiKeyAuthFilter;
import com.example.apiasistente.service.ChatQueueService;
import com.example.apiasistente.service.RagService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;

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
    public ChatResponse chat(@Valid @RequestBody ChatRequest req,
                             Principal principal,
                             HttpServletRequest request) {
        String username = resolveUsername(principal);
        boolean specialKey = Boolean.TRUE.equals(request.getAttribute(ApiKeyAuthFilter.ATTR_SPECIAL_KEY));
        Long apiKeyId = (Long) request.getAttribute(ApiKeyAuthFilter.ATTR_API_KEY_ID);

        String externalUserId = normalizeExternalUserId(req.getExternalUserId());
        boolean specialModeRequested = req.isSpecialMode() || hasText(externalUserId);

        if (specialModeRequested) {
            if (!specialKey) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Esta API key no tiene modo especial habilitado para aislamiento por usuario externo."
                );
            }
            if (!hasText(externalUserId)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "externalUserId es obligatorio cuando specialMode=true."
                );
            }
            if (apiKeyId == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Contexto de API key no disponible.");
            }
            externalUserId = scopedExternalUser(apiKeyId, externalUserId);
        } else {
            externalUserId = null;
        }

        return chatQueueService.chatAndWait(
                username,
                req.getSessionId(),
                req.getMessage(),
                req.getModel(),
                externalUserId
        );
    }

    @PostMapping("/rag/documents")
    public UpsertDocumentResponse upsert(@Valid @RequestBody UpsertDocumentRequest req, Principal principal) {
        String owner = resolveUsername(principal);
        var doc = ragService.upsertDocumentForOwner(owner, req.getTitle(), req.getContent());
        return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
    }

    @PostMapping("/rag/documents/batch")
    public List<UpsertDocumentResponse> upsertBatch(@Valid @RequestBody List<UpsertDocumentRequest> reqs, Principal principal) {
        String owner = resolveUsername(principal);
        return reqs.stream().map(r -> {
            var doc = ragService.upsertDocumentForOwner(owner, r.getTitle(), r.getContent());
            return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
        }).toList();
    }


    @PostMapping("/rag/memory")
    public UpsertDocumentResponse storeMemory(@Valid @RequestBody MemoryRequest req, Principal principal) {
        String owner = resolveUsername(principal);
        var doc = ragService.storeMemory(owner, req.getTitle(), req.getContent());
        return new UpsertDocumentResponse(doc.getId(), doc.getTitle());
    }

    /**
     * Resuelve el usuario autenticado o falla con 401 si el API key no es válido.
     */
    private String resolveUsername(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "API key requerida.");
        }
        return principal.getName();
    }

    private String normalizeExternalUserId(String raw) {
        if (!hasText(raw)) return null;
        String clean = raw.trim();
        if (clean.length() > 120) {
            clean = clean.substring(0, 120);
        }
        return clean;
    }

    private String scopedExternalUser(Long apiKeyId, String externalUserId) {
        return "key:" + apiKeyId + "|user:" + externalUserId;
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
