package com.example.apiasistente.rag.strategy.impl;

import com.example.apiasistente.rag.config.RagRetrievalConfig;
import com.example.apiasistente.rag.service.RagService;
import com.example.apiasistente.rag.strategy.RerankingStrategy;
import com.example.apiasistente.rag.util.VectorMath;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reranker basado en Maximal Marginal Relevance (MMR).
 * Balancea relevancia vs diversidad para evitar redundancia.
 * Implementa la lógica optimizada (incremental) de RagService.rerankWithMmr().
 */
@Component
@RequiredArgsConstructor
public class MmrReranker implements RerankingStrategy {

    private final RagRetrievalConfig config;

    @Override
    public List<RagService.ScoredCandidate> rerank(
            List<RagService.ScoredCandidate> candidates,
            int topK,
            Map<String, Object> params
    ) {
        if (candidates.isEmpty() || topK <= 0) {
            return List.of();
        }

        double lambda = params.containsKey("lambda")
                ? (double) params.get("lambda")
                : config.getMmr().getLambda();

        List<RagService.ScoredCandidate> selected = new ArrayList<>();
        List<RagService.ScoredCandidate> remaining = new ArrayList<>(candidates);

        // Precomputar maxSim inicial (todo 0)
        double[] maxSim = new double[remaining.size()];

        while (selected.size() < topK && !remaining.isEmpty()) {
            int bestIdx = -1;
            double bestMmr = Double.NEGATIVE_INFINITY;

            for (int i = 0; i < remaining.size(); i++) {
                RagService.ScoredCandidate cand = remaining.get(i);
                double relevance = cand.finalScore();

                // MMR = λ*relevance - (1-λ)*max_sim_to_selected
                double mmr = lambda * relevance - (1.0 - lambda) * maxSim[i];

                if (mmr > bestMmr) {
                    bestMmr = mmr;
                    bestIdx = i;
                }
            }

            if (bestIdx == -1) break;

            RagService.ScoredCandidate chosen = remaining.remove(bestIdx);
            selected.add(chosen);

            // Actualizar maxSim solo para los candidatos restantes (incremental O(n) en lugar de O(n²))
            double[] chosenEmb = chosen.embedding();
            for (int i = bestIdx; i < remaining.size(); i++) {
                double sim = VectorMath.cosineSimilarityUnit(chosenEmb, remaining.get(i).embedding());
                maxSim[i] = Math.max(maxSim[i], sim);
            }

            // Reajustar el array maxSim (eliminamos la posición bestIdx)
            double[] newMaxSim = new double[remaining.size()];
            System.arraycopy(maxSim, 0, newMaxSim, 0, bestIdx);
            System.arraycopy(maxSim, bestIdx + 1, newMaxSim, bestIdx, remaining.size() - bestIdx);
            maxSim = newMaxSim;
        }

        return selected;
    }
}
