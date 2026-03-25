package com.example.apiasistente.rag.strategy;

import com.example.apiasistente.rag.service.RagService;

import java.util.List;
import java.util.Set;

/**
 * Estrategia de compresión de contexto.
 * Permite intercambiar algoritmos (extractive, abstractive, LongLLMLingua, etc).
 */
@FunctionalInterface
public interface CompressionStrategy {

    /**
     * Comprime chunks a snippets que caben en el contexto del prompt.
     *
     * @param chunks Chunks ya rerankeados
     * @param query Query original
     * @param queryTokens Tokens pre-computados
     * @param maxChunks Máximo de chunks a retornar
     * @param maxSnippetsPerChunk Máximo de snippets por chunk
     * @param maxCharsPerChunk Máximo de caracteres por chunk
     * @return Lista de chunks comprimidos
     */
    List<RagService.CompressedChunk> compress(
            List<RagService.ScoredCandidate> chunks,
            String query,
            Set<String> queryTokens,
            int maxChunks,
            int maxSnippetsPerChunk,
            int maxCharsPerChunk
    );
}
