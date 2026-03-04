package com.example.apiasistente.rag.controller;

import com.example.apiasistente.apikey.security.ApiKeyAuthFilter;
import com.example.apiasistente.rag.dto.MemoryRequest;
import com.example.apiasistente.rag.dto.UpsertDocumentRequest;
import com.example.apiasistente.rag.dto.UpsertDocumentResponse;
import com.example.apiasistente.rag.entity.KnowledgeDocument;
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

    private final RagService ragService;

    public ExternalRagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/documents")
    public UpsertDocumentResponse upsert(@Valid @RequestBody UpsertDocumentRequest req, Principal principal) {
        resolveUsername(principal);
        return toUpsertResponse(upsertWithOptionalMetadata(RagService.GLOBAL_OWNER, req));
    }

    @PostMapping("/documents/batch")
    public List<UpsertDocumentResponse> upsertBatch(@Valid @RequestBody List<UpsertDocumentRequest> reqs,
                                                    Principal principal) {
        resolveUsername(principal);
        return mapUpsertResponses(reqs, req -> toUpsertResponse(upsertWithOptionalMetadata(RagService.GLOBAL_OWNER, req)));
    }

    @PostMapping("/users/{externalUserId}/documents")
    public UpsertDocumentResponse upsertForExternalUser(@PathVariable String externalUserId,
                                                        @Valid @RequestBody UpsertDocumentRequest req,
                                                        Principal principal,
                                                        HttpServletRequest request) {
        resolveUsername(principal);
        String scopedOwner = resolveScopedExternalOwner(externalUserId, request);
        return toUpsertResponse(upsertWithOptionalMetadata(scopedOwner, req));
    }

    @PostMapping("/users/{externalUserId}/documents/batch")
    public List<UpsertDocumentResponse> upsertBatchForExternalUser(@PathVariable String externalUserId,
                                                                   @Valid @RequestBody List<UpsertDocumentRequest> reqs,
                                                                   Principal principal,
                                                                   HttpServletRequest request) {
        resolveUsername(principal);
        String scopedOwner = resolveScopedExternalOwner(externalUserId, request);
        return mapUpsertResponses(
                reqs,
                req -> toUpsertResponse(upsertWithOptionalMetadata(scopedOwner, req))
        );
    }

    @PostMapping("/memory")
    public UpsertDocumentResponse storeMemory(@Valid @RequestBody MemoryRequest req, Principal principal) {
        String owner = resolveUsername(principal);
        return toUpsertResponse(storeMemoryWithOptionalMetadata(owner, req));
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

    private KnowledgeDocument upsertWithOptionalMetadata(String owner, UpsertDocumentRequest req) {
        // La compatibilidad legacy se conserva aqui tambien para no romper clientes externos existentes.
        if (!req.hasExplicitChunks()
                && (req.getSource() == null || req.getSource().isBlank())
                && (req.getTags() == null || req.getTags().isBlank())
                && (req.getUrl() == null || req.getUrl().isBlank())) {
            return RagService.GLOBAL_OWNER.equals(owner)
                    ? ragService.upsertDocument(req.getTitle(), req.getContent())
                    : ragService.upsertDocumentForOwner(owner, req.getTitle(), req.getContent());
        }
        return ragService.upsertStructuredDocumentForOwner(
                owner,
                req.getTitle(),
                req.getContent(),
                req.getSource(),
                req.getTags(),
                req.getUrl(),
                mapChunks(req)
        );
    }

    private KnowledgeDocument storeMemoryWithOptionalMetadata(String owner, MemoryRequest req) {
        if ((req.getSource() == null || req.getSource().isBlank()) && (req.getTags() == null || req.getTags().isBlank())) {
            return ragService.storeMemory(owner, req.getTitle(), req.getContent());
        }
        String source = req.getSource() == null || req.getSource().isBlank() ? "memory" : req.getSource();
        return ragService.upsertDocumentForOwner(owner, req.getTitle(), req.getContent(), source, req.getTags());
    }

    private List<RagService.IncomingChunk> mapChunks(UpsertDocumentRequest req) {
        if (req.getChunks() == null || req.getChunks().isEmpty()) {
            return List.of();
        }
        return req.getChunks().stream()
                .filter(chunk -> chunk != null)
                .map(chunk -> new RagService.IncomingChunk(
                        chunk.getChunkIndex(),
                        chunk.getText(),
                        chunk.getHash(),
                        chunk.getTokenCount(),
                        chunk.getSource(),
                        chunk.getTags()
                ))
                .toList();
    }

    private List<UpsertDocumentResponse> mapUpsertResponses(List<UpsertDocumentRequest> requests,
                                                            Function<UpsertDocumentRequest, UpsertDocumentResponse> mapper) {
        return requests.stream().map(mapper).toList();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
