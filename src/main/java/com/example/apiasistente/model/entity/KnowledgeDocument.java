package com.example.apiasistente.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "knowledge_document",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_knowledge_document_owner_title",
                columnNames = {"owner", "title"}
        ),
        indexes = {
                @Index(name = "idx_knowledge_document_owner", columnList = "owner")
        }
)
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // "global" = docs compartidos; otros owners = usuario/app
    @Column(nullable = false, length = 120)
    private String owner = "global";

    @Column(nullable = false, length = 200)
    private String title;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // getters/setters
    public Long getId() { return id; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
