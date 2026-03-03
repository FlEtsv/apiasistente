package com.example.apiasistente.rag.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Solicitud para Memory.
 */
public class MemoryRequest {

    private String title;

    @NotBlank
    private String content;

    // Metadata opcional para clasificar memorias dentro del corpus.
    private String source;
    private String tags;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }
}

