package com.example.apiasistente.rag.controller;

import com.example.apiasistente.rag.dto.MemoryRequest;
import com.example.apiasistente.rag.dto.UpsertDocumentRequest;
import com.example.apiasistente.rag.dto.UpsertDocumentResponse;
import com.example.apiasistente.rag.entity.KnowledgeDocument;
import com.example.apiasistente.rag.service.RagService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Principal;
import java.util.ArrayList;
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

    private static final Logger log = LoggerFactory.getLogger(ExternalRagController.class);

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
        return mapUpsertResponsesSafe(reqs, req -> toUpsertResponse(upsertWithOptionalMetadata(RagService.GLOBAL_OWNER, req)));
    }

    @PostMapping("/users/{externalUserId}/documents")
    public UpsertDocumentResponse upsertForExternalUser(@PathVariable String externalUserId,
                                                        @Valid @RequestBody UpsertDocumentRequest req,
                                                        Principal principal) {
        resolveUsername(principal);
        // Compatibilidad legacy: externalUserId ya no define un owner privado; todo entra al corpus global.
        return toUpsertResponse(upsertWithOptionalMetadata(RagService.GLOBAL_OWNER, req));
    }

    @PostMapping("/users/{externalUserId}/documents/batch")
    public List<UpsertDocumentResponse> upsertBatchForExternalUser(@PathVariable String externalUserId,
                                                                   @Valid @RequestBody List<UpsertDocumentRequest> reqs,
                                                                   Principal principal) {
        resolveUsername(principal);
        return mapUpsertResponsesSafe(
                reqs,
                req -> toUpsertResponse(upsertWithOptionalMetadata(RagService.GLOBAL_OWNER, req))
        );
    }

    @PostMapping("/memory")
    public UpsertDocumentResponse storeMemory(@Valid @RequestBody MemoryRequest req, Principal principal) {
        resolveUsername(principal);
        return toUpsertResponse(storeMemoryWithOptionalMetadata(RagService.GLOBAL_OWNER, req));
    }

    private String resolveUsername(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "API key requerida.");
        }
        return principal.getName();
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

    /**
     * Procesa cada documento de forma aislada: un fallo en un item no cancela el resto.
     * Los items fallidos se omiten del resultado; el caller recibe los que sí se ingresaron.
     */
    private List<UpsertDocumentResponse> mapUpsertResponsesSafe(List<UpsertDocumentRequest> requests,
                                                                Function<UpsertDocumentRequest, UpsertDocumentResponse> mapper) {
        List<UpsertDocumentResponse> results = new ArrayList<>(requests.size());
        for (UpsertDocumentRequest req : requests) {
            try {
                results.add(mapper.apply(req));
            } catch (Exception e) {
                log.warn("ext_rag batch_item_fail title='{}' cause={}", req.getTitle(), e.getMessage());
            }
        }
        return results;
    }

}
