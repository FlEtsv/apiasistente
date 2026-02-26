package com.example.apiasistente.service;

import com.example.apiasistente.config.OllamaProperties;
import org.springframework.stereotype.Component;

/**
 * Selector de modelos de chat: permite cambiar entre el modelo principal y el rapido.
 */
@Component
public class ChatModelSelector {

    public static final String DEFAULT_ALIAS = "default";
    public static final String AUTO_ALIAS = "auto";
    public static final String CHAT_ALIAS = "chat";
    public static final String FAST_ALIAS = "fast";
    public static final String VISUAL_ALIAS = "visual";

    private final OllamaProperties properties;

    public ChatModelSelector(OllamaProperties properties) {
        this.properties = properties;
    }

    /**
     * Resuelve el modelo solicitado por el cliente.
     * Politica:
     * - "auto"/"default"/null -> fast por defecto; sube a chat si hay RAG/consulta compleja/multi-paso.
     * - "chat" o nombre exacto del modelo principal -> modelo principal.
     * - "fast" o nombre exacto del modelo rapido -> modelo rapido.
     */
    public String resolveChatModel(String requested) {
        return resolveChatModel(requested, false, false, false);
    }

    public String resolveChatModel(String requested,
                                   boolean hasRagContext,
                                   boolean complexQuery,
                                   boolean multiStepQuery) {
        String trimmed = requested == null ? "" : requested.trim();
        String defaultModel = normalize(properties.getChatModel());
        String fastModel = normalize(properties.getFastChatModel());
        String visualModel = normalize(properties.getVisualModel());

        // Modo auto: usa fast en simple, chat en RAG/consultas complejas/multi-paso.
        if (isAutoRequest(trimmed)) {
            boolean requiresChatModel = hasRagContext || complexQuery || multiStepQuery;
            String resolved = requiresChatModel
                    ? firstNonBlank(defaultModel, fastModel, null)
                    : firstNonBlank(fastModel, defaultModel, null);
            if (resolved == null) {
                throw new IllegalArgumentException("No hay modelos de chat configurados.");
            }
            return resolved;
        }
        // Alias "chat": fuerza el modelo principal.
        if (CHAT_ALIAS.equalsIgnoreCase(trimmed)) {
            String resolved = firstNonBlank(defaultModel, fastModel, null);
            if (resolved == null) {
                throw new IllegalArgumentException("No hay modelos de chat configurados.");
            }
            return resolved;
        }
        // Alias "fast": prioriza fast, cae a principal si falta.
        if (FAST_ALIAS.equalsIgnoreCase(trimmed)) {
            String resolved = firstNonBlank(fastModel, defaultModel, null);
            if (resolved == null) {
                throw new IllegalArgumentException("No hay modelos de chat configurados.");
            }
            return resolved;
        }
        // Alias "visual": el modelo final sigue siendo el principal.
        if (VISUAL_ALIAS.equalsIgnoreCase(trimmed)) {
            String resolved = firstNonBlank(defaultModel, fastModel, null);
            if (resolved == null) {
                throw new IllegalArgumentException("No hay modelos de chat configurados.");
            }
            return resolved;
        }
        // Coincidencia exacta con modelos configurados
        if (defaultModel != null && defaultModel.equalsIgnoreCase(trimmed)) {
            return defaultModel;
        }
        if (fastModel != null && fastModel.equalsIgnoreCase(trimmed)) {
            return fastModel;
        }
        if (visualModel != null && visualModel.equalsIgnoreCase(trimmed)) {
            String resolved = firstNonBlank(defaultModel, fastModel, null);
            if (resolved == null) {
                throw new IllegalArgumentException("No hay modelos de chat configurados.");
            }
            return resolved;
        }

        throw new IllegalArgumentException("Modelo no permitido: " + requested);
    }

    public String resolveVisualModel(String requested) {
        String visualModel = normalize(properties.getVisualModel());
        String defaultModel = normalize(properties.getChatModel());
        String fastModel = normalize(properties.getFastChatModel());
        String trimmed = requested == null ? "" : requested.trim();

        if (visualModel == null) {
            return firstNonBlank(defaultModel, fastModel, null);
        }

        if (trimmed.isEmpty() || VISUAL_ALIAS.equalsIgnoreCase(trimmed)) {
            return visualModel;
        }
        if (visualModel.equalsIgnoreCase(trimmed)) {
            return visualModel;
        }
        // Aunque el usuario pida default/fast, el pipeline visual usa visual-model dedicado.
        if (DEFAULT_ALIAS.equalsIgnoreCase(trimmed) || FAST_ALIAS.equalsIgnoreCase(trimmed)) {
            return visualModel;
        }
        return visualModel;
    }

    public String resolveResponseGuardModel() {
        return normalize(properties.getResponseGuardModel());
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isAutoRequest(String requested) {
        return requested == null
                || requested.isBlank()
                || DEFAULT_ALIAS.equalsIgnoreCase(requested)
                || AUTO_ALIAS.equalsIgnoreCase(requested);
    }
}
