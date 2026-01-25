package com.example.apiasistente.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "chat_message")
public class ChatMessage {

    public enum Role { USER, ASSISTANT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ChatSession session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Lob
    @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String content;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }

    public ChatSession getSession() { return session; }
    public void setSession(ChatSession session) { this.session = session; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Instant getCreatedAt() { return createdAt; }
}
