package com.example.apiasistente.rag.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persistencia durable del embedding de cada chunk.
 *
 * Flujo actual:
 * - Esta tabla actua como respaldo y base para reconstruir el indice HNSW.
 * - El retrieval normal consulta el indice HNSW, no esta tabla.
 * - La clave primaria coincide con chunk_id para mantener el mapping 1:1 simple.
 */
@Entity
@Table(
        name = "vectors",
        indexes = {
                @Index(name = "idx_vectors_created_at", columnList = "createdAt")
        }
)
public class KnowledgeVector {

    @Id
    @Column(name = "chunk_id")
    private Long chunkId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "chunk_id")
    private KnowledgeChunk chunk;

    @Lob
    @Column(name = "embedding_json", columnDefinition = "LONGTEXT", nullable = false)
    private String embeddingJson;

    @Column(nullable = false)
    private int dimension;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getChunkId() {
        return chunkId;
    }

    public KnowledgeChunk getChunk() {
        return chunk;
    }

    public void setChunk(KnowledgeChunk chunk) {
        this.chunk = chunk;
    }

    public String getEmbeddingJson() {
        return embeddingJson;
    }

    public void setEmbeddingJson(String embeddingJson) {
        this.embeddingJson = embeddingJson;
    }

    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
