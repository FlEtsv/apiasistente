package com.example.apiasistente.service;

import com.example.apiasistente.config.OllamaProperties;
import org.springframework.stereotype.Component;

/**
 * Selector de modelos de chat: permite cambiar entre el modelo principal y el rápido.
 */
@Component
public class ChatModelSelector {

    public static final String DEFAULT_ALIAS = "default";
    public static final String FAST_ALIAS = "fast";

    private final OllamaProperties properties;

    public ChatModelSelector(OllamaProperties properties) {
        this.properties = properties;
    }

    /**
     * Resuelve el modelo solicitado por el cliente.
     * - null o vacío -> modelo principal
     * - "default" o nombre exacto -> modelo principal
     * - "fast" o nombre exacto -> modelo rápido
     */
    public String resolveChatModel(String requested) {
        String trimmed = requested == null ? "" : requested.trim();
        String defaultModel = properties.getChatModel();
        String fastModel = properties.getFastChatModel();

        if (trimmed.isEmpty() || DEFAULT_ALIAS.equalsIgnoreCase(trimmed)) {
            return defaultModel;
        }
        if (FAST_ALIAS.equalsIgnoreCase(trimmed)) {
            return fastModel;
        }
        if (defaultModel != null && defaultModel.equalsIgnoreCase(trimmed)) {
            return defaultModel;
        }
        if (fastModel != null && fastModel.equalsIgnoreCase(trimmed)) {
            return fastModel;
        }

        throw new IllegalArgumentException("Modelo no permitido: " + requested);
    }
}
