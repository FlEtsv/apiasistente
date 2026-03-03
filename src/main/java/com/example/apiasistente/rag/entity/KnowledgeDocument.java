package com.example.apiasistente.rag.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;

/**
 * Documento canonico del RAG.
 *
 * Flujo actual:
 * - Aqui guardamos la identidad del documento y su metadata estable.
 * - El texto operativo vive en la tabla append-only de chunks.
 * - Los embeddings ya no cuelgan del documento ni del chunk: van al vector store/HNSW.
 */
@Entity
@Table(
        name = "documents",
        indexes = {
                @Index(name = "idx_documents_owner_active", columnList = "owner,active"),
                @Index(name = "idx_documents_owner_title", columnList = "owner,title"),
                @Index(name = "idx_documents_source", columnList = "source")
        }
)
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "doc_id")
    private Long id;

    // "global" = corpus comun; el resto son espacios de conocimiento aislados.
    @Column(nullable = false, length = 120)
    private String owner = "global";

    @Column(nullable = false, length = 200)
    private String title;

    // Origen del documento: api, memory, maintenance, import, etc.
    @Column(nullable = false, length = 160)
    private String source = "api";

    // URL original cuando el documento viene de scraper/crawler.
    @Column(length = 1000)
    private String referenceUrl;

    // Permite versionar sin reescribir filas antiguas y mantener chunks append-only.
    @Column(nullable = false)
    private boolean active = true;

    @Column(length = 128)
    private String contentFingerprint;

    @Column
    private Long supersededByDocumentId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    // Compatibilidad temporal con tests y código legacy; ya no se persiste ni se usa como fuente canonica.
    @Transient
    private String content;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (source == null || source.isBlank()) {
            source = "api";
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getReferenceUrl() {
        return referenceUrl;
    }

    public void setReferenceUrl(String referenceUrl) {
        this.referenceUrl = referenceUrl;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getContentFingerprint() {
        return contentFingerprint;
    }

    public void setContentFingerprint(String contentFingerprint) {
        this.contentFingerprint = contentFingerprint;
    }

    public Long getSupersededByDocumentId() {
        return supersededByDocumentId;
    }

    public void setSupersededByDocumentId(Long supersededByDocumentId) {
        this.supersededByDocumentId = supersededByDocumentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Campo legado solo para compatibilidad en tests/mocks.
     * El contenido real del documento debe reconstruirse desde chunks.
     */
    public String getContent() {
        return content;
    }

    /**
     * Campo legado solo para compatibilidad en tests/mocks.
     * No persiste en la nueva estructura.
     */
    public void setContent(String content) {
        this.content = content;
    }
}

