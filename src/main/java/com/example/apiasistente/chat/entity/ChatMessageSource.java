package com.example.apiasistente.chat.entity;

import com.example.apiasistente.rag.entity.KnowledgeChunk;
import jakarta.persistence.*;

/**
 * Entidad que representa Chat Message Source.
 */
@Entity
@Table(name = "chat_message_source")
public class ChatMessageSource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="message_id", nullable=false)
    private ChatMessage message;

    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="chunk_id", nullable=false)
    private KnowledgeChunk chunk;

    @Column(nullable=false)
    private double score;

    public Long getId() { return id; }

    public ChatMessage getMessage() { return message; }
    public void setMessage(ChatMessage message) { this.message = message; }

    public KnowledgeChunk getChunk() { return chunk; }
    public void setChunk(KnowledgeChunk chunk) { this.chunk = chunk; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
}

