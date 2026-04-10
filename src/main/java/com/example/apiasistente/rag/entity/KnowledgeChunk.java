package com.example.apiasistente.rag.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;

/**
 * Fragmento append-only del documento.
 *
 * Flujo actual:
 * - Cada nueva ingesta crea nuevos chunks.
 * - El texto del chunk es la unidad que consume retrieval.
 * - La metadata aqui permite auditar calidad sin rearmar el documento entero.
 *
 * Responsabilidad:
 * - Conservar el texto canonico que realmente entra en el prompt.
 * - Hacer visible la calidad del particionado para mantenimiento y depuracion.
 */
@Entity
@Table(
        name = "chunks",
        indexes = {
                @Index(name = "idx_chunks_doc_idx", columnList = "doc_id,chunkIndex"),
                @Index(name = "idx_chunks_hash", columnList = "hash"),
                @Index(name = "idx_chunks_source", columnList = "source")
        }
)
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chunk_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doc_id", nullable = false)
    private KnowledgeDocument document;

    @Column(nullable = false)
    private int chunkIndex;

    @Column(name = "text", columnDefinition = "LONGTEXT", nullable = false)
    private String text;

    @Column(nullable = false, length = 128)
    private String hash;

    @Column(nullable = false)
    private int tokenCount;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false, length = 160)
    private String source = "api";

    // Se deja serializado como CSV simple para no complicar la edicion manual.
    @Column(length = 1000)
    private String tags;

    // Compatibilidad temporal con tests/mocks antiguos.
    @Transient
    private String embeddingJson;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (source == null || source.isBlank()) {
            source = document != null ? document.getSource() : "api";
        }
        if (hash == null) {
            hash = "";
        }
    }

    public Long getId() {
        return id;
    }

    public KnowledgeDocument getDocument() {
        return document;
    }

    public void setDocument(KnowledgeDocument document) {
        this.document = document;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(int tokenCount) {
        this.tokenCount = tokenCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    /**
     * Campo legado solo para compatibilidad con tests antiguos.
     * En la nueva estructura el embedding vive en la capa de vectores.
     */
    public String getEmbeddingJson() {
        return embeddingJson;
    }

    /**
     * Campo legado solo para compatibilidad con tests antiguos.
     * En la nueva estructura el embedding vive en la capa de vectores.
     */
    public void setEmbeddingJson(String embeddingJson) {
        this.embeddingJson = embeddingJson;
    }
}

