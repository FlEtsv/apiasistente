package com.example.apiasistente.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuración centralizada para todo el pipeline de retrieval RAG.
 * Reemplaza los múltiples @Value dispersos en RagService.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag")
public class RagRetrievalConfig {

    private int topK = 10;
    private int rerankCandidates = 12;
    private double evidenceThreshold = 0.45;

    private ChunkingConfig chunking = new ChunkingConfig();
    private FusionConfig fusion = new FusionConfig();
    private MmrConfig mmr = new MmrConfig();
    private CompressionConfig compression = new CompressionConfig();
    private OwnerBoostConfig ownerBoost = new OwnerBoostConfig();

    @Data
    public static class ChunkingConfig {
        private int size = 900;
        private int overlap = 150;
        private String strategy = "sliding-window"; // sliding-window | semantic | recursive
    }

    @Data
    public static class FusionConfig {
        private double semanticWeight = 0.80;
        private double lexicalWeight = 0.20;
        private double exactMatchBoost = 0.12;
        private double coverageWeight = 0.75;
        private double jaccardWeight = 0.25;
    }

    @Data
    public static class MmrConfig {
        private double lambda = 0.65; // relevance vs diversity
    }

    @Data
    public static class CompressionConfig {
        private int maxChunks = 5;
        private int maxSnippetsPerChunk = 2;
        private int maxCharsPerChunk = 420;
    }

    @Data
    public static class OwnerBoostConfig {
        private double globalBoost = 0.03;
        private double userBoost = 0.05;
    }
}
