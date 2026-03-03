package com.example.apiasistente.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Snapshot de evidencia usada por una respuesta del asistente.
 *
 * No se referencia al chunk vivo del RAG para que el historial siga siendo estable
 * aunque el corpus se pode, reindexe o borre documentos.
 */
@Entity
@Table(
        name = "chat_message_source",
        indexes = {
                @Index(name = "idx_chat_message_source_doc", columnList = "source_document_id"),
                @Index(name = "idx_chat_message_source_chunk", columnList = "source_chunk_id")
        }
)
public class ChatMessageSource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private ChatMessage message;

    @Column(name = "source_chunk_id")
    private Long sourceChunkId;

    // Se deja nullable para poder absorber filas legacy y rellenarlas en el migrador.
    @Column(name = "source_document_id")
    private Long sourceDocumentId;

    @Column(name = "source_document_title", length = 200)
    private String sourceDocumentTitle;

    @Lob
    @Column(name = "source_snippet", columnDefinition = "LONGTEXT")
    private String sourceSnippet;

    @Column(nullable = false)
    private double score;

    public Long getId() { return id; }

    public ChatMessage getMessage() { return message; }
    public void setMessage(ChatMessage message) { this.message = message; }

    public Long getSourceChunkId() { return sourceChunkId; }
    public void setSourceChunkId(Long sourceChunkId) { this.sourceChunkId = sourceChunkId; }

    public Long getSourceDocumentId() { return sourceDocumentId; }
    public void setSourceDocumentId(Long sourceDocumentId) { this.sourceDocumentId = sourceDocumentId; }

    public String getSourceDocumentTitle() { return sourceDocumentTitle; }
    public void setSourceDocumentTitle(String sourceDocumentTitle) { this.sourceDocumentTitle = sourceDocumentTitle; }

    public String getSourceSnippet() { return sourceSnippet; }
    public void setSourceSnippet(String sourceSnippet) { this.sourceSnippet = sourceSnippet; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
}

