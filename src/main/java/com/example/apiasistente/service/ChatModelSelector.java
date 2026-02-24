package com.example.apiasistente.service;

import com.example.apiasistente.config.OllamaProperties;
import org.springframework.stereotype.Component;

/**
 * Selector de modelos de chat: permite cambiar entre el modelo principal y el rapido.
 */
@Component
public class ChatModelSelector {

    public static final String DEFAULT_ALIAS = "default";
    public static final String FAST_ALIAS = "fast";
    public static final String VISUAL_ALIAS = "visual";

    private final OllamaProperties properties;

    public ChatModelSelector(OllamaProperties properties) {
        this.properties = properties;
    }

    /**
     * Resuelve el modelo solicitado por el cliente.
     * - null o vacio -> modelo principal
     * - "default" o nombre exacto -> modelo principal
     * - "fast" o nombre exacto -> modelo rapido
     */
    public String resolveChatModel(String requested) {
        String trimmed = requested == null ? "" : requested.trim();
        String defaultModel = normalize(properties.getChatModel());
        String fastModel = normalize(properties.getFastChatModel());
        String visualModel = normalize(properties.getVisualModel());

        // Sin preferencia: elige principal o rapido como fallback.
        if (trimmed.isEmpty() || DEFAULT_ALIAS.equalsIgnoreCase(trimmed)) {
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
}
