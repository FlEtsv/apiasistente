package com.example.apiasistente.rag.service;

import com.example.apiasistente.rag.dto.MemoryRequest;
import com.example.apiasistente.rag.dto.UpsertDocumentRequest;
import com.example.apiasistente.rag.entity.KnowledgeDocument;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fachada de ingesta RAG para controladores HTTP.
 * <p>
 * Centraliza compatibilidad legacy y mapeo de DTOs web hacia el contrato interno
 * de {@link RagService}, evitando duplicacion entre APIs interna y externa.
 */
@Service
public class RagIngestionService {

    private final RagService ragService;

    public RagIngestionService(RagService ragService) {
        this.ragService = ragService;
    }

    /**
     * Inserta/actualiza un documento para un owner concreto.
     * <p>
     * Si el request no trae metadata/chunks explicitos, mantiene el flujo legacy.
     *
     * @param owner owner destino (global o scoped)
     * @param req payload de documento
     * @return documento resultante
     */
    public KnowledgeDocument upsert(String owner, UpsertDocumentRequest req) {
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

    /**
     * Inserta una memoria para owner con compatibilidad de metadata opcional.
     *
     * @param owner owner destino
     * @param req payload de memoria
     * @return documento de memoria persistido
     */
    public KnowledgeDocument storeMemory(String owner, MemoryRequest req) {
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
}
