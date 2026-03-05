package com.example.apiasistente.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuracion del trail de auditoria persistente del chat.
 */
@Component
@ConfigurationProperties(prefix = "chat.audit")
public class ChatAuditProperties {

    /**
     * Activa escritura de eventos de trazabilidad en archivo local.
     */
    private boolean enabled = true;

    /**
     * Ruta del archivo JSONL donde se append-ean eventos.
     */
    private String filePath = "data/audit/chat-trace.jsonl";

    /**
     * Maximo de caracteres para previews de prompt en eventos.
     */
    private int maxPreviewChars = 220;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public int getMaxPreviewChars() {
        return maxPreviewChars;
    }

    public void setMaxPreviewChars(int maxPreviewChars) {
        this.maxPreviewChars = maxPreviewChars;
    }
}
