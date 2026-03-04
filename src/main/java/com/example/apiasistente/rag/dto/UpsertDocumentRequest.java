package com.example.apiasistente.rag.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.List;

/**
 * Solicitud de ingesta RAG.
 *
 * Compatibilidad:
 * - `content` sigue funcionando para clientes legacy.
 * - `chunks` es la forma preferida para scraper y productores nuevos.
 *
 * Responsabilidad:
 * - Describir un documento canonico de entrada.
 * - Permitir edicion manual sin mezclar DTOs web con el core del servicio.
 */
public class UpsertDocumentRequest {
    @NotBlank
    private String title;

    // Cuerpo legacy del documento. Si llega vacio, `chunks` pasa a ser obligatorio.
    private String content;

    // Metadata opcional de la nueva estructura canonica.
    private String source;
    private String tags;
    private String url;

    // Unidad preferida de intercambio para scraper e integraciones modernas.
    @Valid
    private List<UpsertChunkRequest> chunks = new ArrayList<>();

    @AssertTrue(message = "Debes enviar content o al menos un chunk.")
    public boolean hasPayload() {
        if (content != null && !content.isBlank()) {
            return true;
        }
        return chunks != null && chunks.stream().anyMatch(chunk -> chunk != null && chunk.getText() != null && !chunk.getText().isBlank());
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public List<UpsertChunkRequest> getChunks() { return chunks; }
    public void setChunks(List<UpsertChunkRequest> chunks) { this.chunks = chunks; }

    public boolean hasExplicitChunks() {
        return chunks != null && chunks.stream().anyMatch(chunk -> chunk != null && chunk.getText() != null && !chunk.getText().isBlank());
    }
}

