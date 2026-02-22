package com.example.apiasistente.model.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "chat_session")
public class ChatSession {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "system_prompt_id")
    private SystemPrompt systemPrompt;

    @Column(nullable = false, length = 120)
    private String title = "Nuevo chat";

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant lastActivityAt = Instant.now();

    /**
     * Namespace opcional para aislar conversaciones de usuarios finales en integraciones externas.
     * Null = sesion generica (web y API externa sin modo especial).
     */
    @Column(name = "external_user_id", length = 160)
    private String externalUserId;

    @OneToMany(mappedBy = "session", cascade = CascadeType.REMOVE, orphanRemoval = true)
    private List<ChatMessage> messages = new ArrayList<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }

    public SystemPrompt getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(SystemPrompt systemPrompt) { this.systemPrompt = systemPrompt; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getLastActivityAt() { return lastActivityAt; }
    public void setLastActivityAt(Instant lastActivityAt) { this.lastActivityAt = lastActivityAt; }

    public String getExternalUserId() { return externalUserId; }
    public void setExternalUserId(String externalUserId) { this.externalUserId = externalUserId; }

    public List<ChatMessage> getMessages() { return messages; }
}
