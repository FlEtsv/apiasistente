package com.example.apiasistente.rag.entity;

/**
 * Tipos de problemas detectados en el corpus RAG.
 */
public enum RagMaintenanceIssueType {
    BAD_STRUCTURE,
    DUPLICATE_DOCUMENT,
    UNUSED_DOCUMENT,
    ILLEGIBLE_CONTENT,
    LOW_VALUE_CONTENT,
    /** Chunks del mismo documento contienen hechos contradictorios o incoherentes entre si. */
    INCOHERENT_CONTENT
}
