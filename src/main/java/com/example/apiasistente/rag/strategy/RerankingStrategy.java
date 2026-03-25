package com.example.apiasistente.rag.strategy;

import com.example.apiasistente.rag.service.RagService;

import java.util.List;
import java.util.Map;

/**
 * Estrategia de reranking para diversidad/calidad.
 * Permite intercambiar algoritmos (MMR, cross-encoder, diversity, etc).
 */
@FunctionalInterface
public interface RerankingStrategy {

    /**
     * Reordena candidatos según criterio de diversidad/calidad.
     *
     * @param candidates Candidatos ya puntuados
     * @param topK Cantidad final deseada
     * @param params Parámetros específicos (e.g., lambda para MMR)
     * @return Lista reordenada (size <= topK)
     */
    List<RagService.ScoredCandidate> rerank(
            List<RagService.ScoredCandidate> candidates,
            int topK,
            Map<String, Object> params
    );
}
