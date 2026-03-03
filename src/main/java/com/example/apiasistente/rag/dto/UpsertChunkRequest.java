package com.example.apiasistente.rag.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Chunk explicito enviado por scraper o integraciones externas.
 *
 * La idea es que el productor ya entregue la unidad canonica del sistema
 * en vez de delegar todo el chunking al endpoint.
 */
public class UpsertChunkRequest {

    private Integer chunkIndex;

    @NotBlank
    private String text;

    private String hash;
    private Integer tokenCount;
    private String source;
    private String tags;

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
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
