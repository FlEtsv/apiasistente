package com.example.apiasistente.rag.dto;

/**
 * Snapshot compacto del tamano actual del corpus RAG.
 */
public record RagMaintenanceCorpusDto(long totalDocuments,
                                      long totalChunks,
                                      long documentBytes,
                                      long chunkTextBytes,
                                      long embeddingBytes,
                                      long totalBytes) {

    public RagMaintenanceCorpusDto {
        totalDocuments = Math.max(0, totalDocuments);
        totalChunks = Math.max(0, totalChunks);
        documentBytes = Math.max(0, documentBytes);
        chunkTextBytes = Math.max(0, chunkTextBytes);
        embeddingBytes = Math.max(0, embeddingBytes);
        totalBytes = Math.max(0, totalBytes);
    }

    public static RagMaintenanceCorpusDto empty() {
        return new RagMaintenanceCorpusDto(0, 0, 0, 0, 0, 0);
    }
}
