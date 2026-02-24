package com.example.apiasistente.model.dto;

/**
 * Adjuntos opcionales para consultas visuales/documentales en chat.
 * - Para imagen/pdf/binario: enviar base64 (sin prefijo data:).
 * - Para texto plano: enviar text.
 */
public class ChatMediaInput {
    private String name;
    private String mimeType;
    private String base64;
    private String text;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getBase64() {
        return base64;
    }

    public void setBase64(String base64) {
        this.base64 = base64;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
