package com.example.apiasistente.chat.service;

import com.example.apiasistente.chat.config.ChatAuditProperties;
import com.example.apiasistente.shared.util.RequestIdHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Escribe eventos de trazabilidad en un archivo JSONL persistente.
 * Se usa para auditar decisiones aunque el turno falle o se marque rollback.
 */
@Service
public class ChatAuditTrailService {

    private static final Logger log = LoggerFactory.getLogger(ChatAuditTrailService.class);

    private final ChatAuditProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Object writeLock = new Object();

    public ChatAuditTrailService(ChatAuditProperties properties) {
        this.properties = properties;
    }

    /**
     * Registra un evento con payload plano en el trail JSONL.
     */
    public void record(String event, Map<String, Object> payload) {
        if (!properties.isEnabled()) {
            return;
        }
        if (event == null || event.isBlank()) {
            return;
        }

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("timestamp", Instant.now().toString());
        root.put("requestId", resolveRequestId());
        root.put("event", event.trim());
        root.put("data", payload == null ? Map.of() : new LinkedHashMap<>(payload));

        appendLine(root);
    }

    /**
     * Crea una preview corta y estable para prompts/texto de usuario.
     */
    public String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int max = Math.max(40, properties.getMaxPreviewChars());
        String clean = value.replaceAll("\\s+", " ").trim();
        if (clean.length() <= max) {
            return clean;
        }
        return clean.substring(0, max).trim() + "...";
    }

    private String resolveRequestId() {
        String id = RequestIdHolder.get();
        return (id == null || id.isBlank()) ? RequestIdHolder.generate() : id;
    }

    private void appendLine(Map<String, Object> event) {
        Path path = resolvePath();
        try {
            String json = objectMapper.writeValueAsString(event);
            synchronized (writeLock) {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(
                        path,
                        json + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND
                );
            }
        } catch (Exception ex) {
            log.warn("No se pudo escribir evento de auditoria '{}': {}", event.get("event"), ex.getMessage());
        }
    }

    private Path resolvePath() {
        String raw = properties.getFilePath();
        if (raw == null || raw.isBlank()) {
            return Path.of("data/audit/chat-trace.jsonl");
        }
        return Path.of(raw.trim());
    }
}
