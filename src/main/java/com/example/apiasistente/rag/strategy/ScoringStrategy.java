package com.example.apiasistente.rag.strategy;

import com.example.apiasistente.rag.service.RagService;

import java.util.Map;

/**
 * Estrategia de scoring para candidatos de retrieval.
 * Permite intercambiar algoritmos de puntuación (cosine, BM25, hybrid, RRF, etc).
 */
@FunctionalInterface
public interface ScoringStrategy {

    /**
     * Calcula score final para un candidato.
     *
     * @param candidate Chunk candidato con score semántico
     * @param query Query original del usuario
     * @param queryTokens Tokens pre-computados de la query (para evitar re-tokenización)
     * @param context Contexto adicional (owner, boosts, etc)
     * @return Score final (0.0 - 1.0+)
     */
    double score(
            RagService.CandidateChunk candidate,
            String query,
            java.util.Set<String> queryTokens,
            Map<String, Object> context
    );
}
