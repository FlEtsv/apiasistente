package com.example.apiasistente.model.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "knowledge_chunk")
public class KnowledgeChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private KnowledgeDocument document;

    @Column(nullable = false)
    private int chunkIndex;

    @Lob
    @Column(name = "text", columnDefinition = "LONGTEXT", nullable = false)
    private String text;

    // Embedding como JSON (persistido en MySQL)
    @Lob
    @Column(name = "embedding_json", columnDefinition = "LONGTEXT", nullable = false)
    private String embeddingJson;

    public Long getId() { return id; }

    public KnowledgeDocument getDocument() { return document; }
    public void setDocument(KnowledgeDocument document) { this.document = document; }

    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getEmbeddingJson() { return embeddingJson; }
    public void setEmbeddingJson(String embeddingJson) { this.embeddingJson = embeddingJson; }
}
