package com.example.apiasistente.model.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "system_prompt")
public class SystemPrompt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable=false, unique=true)
    private String name;

    @Lob
    @Column(nullable=false)
    private String content;

    @Column(nullable=false)
    private boolean active;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
