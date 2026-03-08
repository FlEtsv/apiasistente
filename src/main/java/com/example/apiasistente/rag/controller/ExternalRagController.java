package com.example.apiasistente.rag.controller;

import com.example.apiasistente.apikey.security.ApiKeyAuthFilter;
import com.example.apiasistente.rag.dto.MemoryRequest;
import com.example.apiasistente.rag.dto.UpsertDocumentRequest;
import com.example.apiasistente.rag.dto.UpsertDocumentResponse;
import com.example.apiasistente.rag.entity.KnowledgeDocument;
import com.example.apiasistente.rag.service.RagIngestionService;
import com.example.apiasistente.rag.service.RagService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.function.Function;

/**
 * API externa del RAG para integraciones con API key.
 *
 * Responsabilidad:
 * - Aplicar las reglas de seguridad del canal externo.
 * - Mantener el mismo flujo canonico de ingesta que la API interna.
 */
@RestController
@RequestMapping("/api/ext/rag")
public class ExternalRagController {

    private final RagIngestionService ragIngestionService;

    public ExternalRagController(RagIngestionService ragIngestionService) {
        this.ragIngestionService = ragIngestionService;
    }

    /**
     * Inserta/actualiza documento global via API externa.
     */
    @PostMapping("/documents")
    public UpsertDocumentResponse upsert(@Valid @RequestBody UpsertDocumentRequest req, Principal principal) {
        resolveUsername(principal);
        return toUpsertResponse(ragIngestionService.upsert(RagService.GLOBAL_OWNER, req));
    }

    /**
     * Inserta/actualiza lote global via API externa.
     */
    @PostMapping("/documents/batch")
    public List<UpsertDocumentResponse> upsertBatch(@Valid @RequestBody List<UpsertDocumentRequest> reqs,
                                                    Principal principal) {
        resolveUsername(principal);
        return mapUpsertResponses(reqs, req -> toUpsertResponse(ragIngestionService.upsert(RagService.GLOBAL_OWNER, req)));
    }

    /**
     * Inserta/actualiza documento scoped por API key especial y external user.
     */
    @PostMapping("/users/{externalUserId}/documents")
    public UpsertDocumentResponse upsertForExternalUser(@PathVariable String externalUserId,
                                                        @Valid @RequestBody UpsertDocumentRequest req,
                                                        Principal principal,
                                                        HttpServletRequest request) {
        resolveUsername(principal);
        String scopedOwner = resolveScopedExternalOwner(externalUserId, request);
        return toUpsertResponse(ragIngestionService.upsert(scopedOwner, req));
    }

    /**
     * Inserta/actualiza lote scoped por API key especial y external user.
     */
    @PostMapping("/users/{externalUserId}/documents/batch")
    public List<UpsertDocumentResponse> upsertBatchForExternalUser(@PathVariable String externalUserId,
                                                                   @Valid @RequestBody List<UpsertDocumentRequest> reqs,
                                                                   Principal principal,
                                                                   HttpServletRequest request) {
        resolveUsername(principal);
        String scopedOwner = resolveScopedExternalOwner(externalUserId, request);
        return mapUpsertResponses(
                reqs,
                req -> toUpsertResponse(ragIngestionService.upsert(scopedOwner, req))
        );
    }

    /**
     * Guarda memoria asociada al owner de la API key.
     */
    @PostMapping("/memory")
    public UpsertDocumentResponse storeMemory(@Valid @RequestBody MemoryRequest req, Principal principal) {
        String owner = resolveUsername(principal);
        return toUpsertResponse(ragIngestionService.storeMemory(owner, req));
    }

    private String resolveUsername(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "API key requerida.");
        }
        return principal.getName();
    }

    private String resolveScopedExternalOwner(String rawExternalUserId, HttpServletRequest request) {
        boolean specialKey = Boolean.TRUE.equals(request.getAttribute(ApiKeyAuthFilter.ATTR_SPECIAL_KEY));
        if (!specialKey) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Esta API key no tiene modo especial habilitado para RAG por usuario externo."
            );
        }

        Long apiKeyId = (Long) request.getAttribute(ApiKeyAuthFilter.ATTR_API_KEY_ID);
        if (apiKeyId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Contexto de API key no disponible.");
        }

        String externalUserId = normalizeExternalUserId(rawExternalUserId);
        if (!hasText(externalUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "externalUserId es obligatorio.");
        }

        return "key:" + apiKeyId + "|user:" + externalUserId;
    }

    private String normalizeExternalUserId(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        String clean = raw.trim();
        if (clean.length() > 120) {
            clean = clean.substring(0, 120);
        }
        return clean;
    }

    private UpsertDocumentResponse toUpsertResponse(KnowledgeDocument document) {
        return new UpsertDocumentResponse(document.getId(), document.getTitle());
    }

    private List<UpsertDocumentResponse> mapUpsertResponses(List<UpsertDocumentRequest> requests,
                                                            Function<UpsertDocumentRequest, UpsertDocumentResponse> mapper) {
        return requests.stream().map(mapper).toList();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
