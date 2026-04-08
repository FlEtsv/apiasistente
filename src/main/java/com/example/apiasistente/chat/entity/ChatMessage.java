package com.example.apiasistente.chat.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Entidad que representa Chat Message.
 */
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
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * JSON de metadatos del mensaje. Para mensajes de usuario: referencias de media adjunta.
     * Para respuestas del asistente: modelo usado, ruta, pipeline, info de generacion de imagen.
     */
    @Lob
    @Column(nullable = true, columnDefinition = "LONGTEXT")
    private String metadata;

    @OneToMany(mappedBy = "message", cascade = CascadeType.REMOVE, orphanRemoval = true)
    private List<ChatMessageSource> sources = new ArrayList<>();

    public Long getId() { return id; }

    public ChatSession getSession() { return session; }
    public void setSession(ChatSession session) { this.session = session; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Instant getCreatedAt() { return createdAt; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public List<ChatMessageSource> getSources() { return sources; }
}

