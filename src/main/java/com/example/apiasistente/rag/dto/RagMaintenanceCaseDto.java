package com.example.apiasistente.rag.dto;

import java.time.Instant;

/**
 * Caso visible en la cola de decisiones del robot RAG.
 */
public record RagMaintenanceCaseDto(Long id,
                                    Long documentId,
                                    String owner,
                                    String documentTitle,
                                    String severity,
                                    String issueType,
                                    String status,
                                    String recommendedAction,
                                    String aiSuggestedAction,
                                    String finalAction,
                                    long usageCount,
                                    Instant lastUsedAt,
                                    Instant createdAt,
                                    Instant updatedAt,
                                    Instant adminDueAt,
                                    Instant aiDecidedAt,
                                    Instant autoApplyAt,
                                    Instant resolvedAt,
                                    String summary,
                                    String originalSnippet,
                                    String proposedContent,
                                    String aiReason,
                                    String aiModel,
                                    String resolvedBy,
                                    String auditLog) {
}
