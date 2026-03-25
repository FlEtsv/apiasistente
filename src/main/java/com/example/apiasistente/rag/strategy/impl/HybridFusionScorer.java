package com.example.apiasistente.rag.strategy.impl;

import com.example.apiasistente.rag.config.RagRetrievalConfig;
import com.example.apiasistente.rag.service.RagService;
import com.example.apiasistente.rag.strategy.ScoringStrategy;
import com.example.apiasistente.rag.util.TextNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Estrategia de scoring híbrido: combina similitud semántica (vector) + lexical (tokens).
 * Implementa la lógica actual de RagService.applyHybridScoring().
 */
@Component
@RequiredArgsConstructor
public class HybridFusionScorer implements ScoringStrategy {

    private final RagRetrievalConfig config;

    @Override
    public double score(
            RagService.CandidateChunk candidate,
            String query,
            Set<String> queryTokens,
            Map<String, Object> context
    ) {
        double semanticScore = candidate.semanticScore();

        // Lexical scoring (token overlap + exact match)
        String normalizedChunk = TextNormalizer.normalize(candidate.chunk().getText());
        Set<String> chunkTokens = TextNormalizer.tokenize(normalizedChunk);

        Set<String> intersection = new java.util.HashSet<>(queryTokens);
        intersection.retainAll(chunkTokens);

        double coverage = chunkTokens.isEmpty() ? 0.0 : (double) intersection.size() / chunkTokens.size();
        double jaccard = (queryTokens.size() + chunkTokens.size() - intersection.size()) == 0 ? 0.0
                : (double) intersection.size() / (queryTokens.size() + chunkTokens.size() - intersection.size());

        double lexical = config.getFusion().getCoverageWeight() * coverage
                + config.getFusion().getJaccardWeight() * jaccard;

        // Exact match boost
        double exactMatchBoost = 0.0;
        String normalizedQuery = TextNormalizer.normalize(query);
        if (normalizedChunk.contains(normalizedQuery) || normalizedQuery.contains(normalizedChunk)) {
            exactMatchBoost = config.getFusion().getExactMatchBoost();
        }

        // Owner boost
        double ownerBoost = 0.0;
        String requestedOwner = (String) context.get("requestedOwner");
        if (requestedOwner != null && requestedOwner.equals(candidate.chunk().getOwner())) {
            ownerBoost = config.getOwnerBoost().getUserBoost();
        } else {
            ownerBoost = config.getOwnerBoost().getGlobalBoost();
        }

        // Fusión ponderada
        double semanticWeight = config.getFusion().getSemanticWeight();
        double lexicalWeight = config.getFusion().getLexicalWeight();

        return clamp01(semanticScore) * semanticWeight
                + lexical * lexicalWeight
                + exactMatchBoost
                + ownerBoost;
    }

    private double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
