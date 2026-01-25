package com.example.apiasistente.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "chat_session")
public class ChatSession {
    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "system_prompt_id")
    private SystemPrompt systemPrompt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public SystemPrompt getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(SystemPrompt systemPrompt) { this.systemPrompt = systemPrompt; }

    public Instant getCreatedAt() { return createdAt; }
}
