package com.example.apiasistente.chat.service;

import com.example.apiasistente.shared.config.OllamaProperties;
import org.springframework.stereotype.Component;

/**
 * Resuelve que modelo debe usar cada etapa del flujo de chat.
 * Centraliza alias publicos y reglas de fallback entre modelos configurados.
 */
@Component
public class ChatModelSelector {

    public static final String DEFAULT_ALIAS = "default";
    public static final String AUTO_ALIAS = "auto";
    public static final String CHAT_ALIAS = "chat";
    public static final String FAST_ALIAS = "fast";
    public static final String VISUAL_ALIAS = "visual";
    public static final String IMAGE_ALIAS = "image";

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

    /**
     * Variante con senales basicas del turno para decidir si conviene subir del modelo rapido al principal.
     */
    public String resolveChatModel(String requested,
                                   boolean hasRagContext,
                                   boolean complexQuery,
                                   boolean multiStepQuery) {
        return resolveChatModel(
                requested,
                hasRagContext,
                complexQuery,
                multiStepQuery,
                ChatPromptSignals.IntentRoute.TASK_SIMPLE
        );
    }

    /**
     * Resuelve el modelo final del turno considerando alias, rutas heuristicas y modelos configurados.
     */
    public String resolveChatModel(String requested,
                                   boolean hasRagContext,
                                   boolean complexQuery,
                                   boolean multiStepQuery,
                                   ChatPromptSignals.IntentRoute intentRoute) {
        String trimmed = requested == null ? "" : requested.trim();
        String defaultModel = normalize(properties.getChatModel());
        String fastModel = normalize(properties.getFastChatModel());
        String visualModel = normalize(properties.getVisualModel());

        ChatPromptSignals.IntentRoute route = intentRoute == null
                ? ChatPromptSignals.IntentRoute.TASK_SIMPLE
                : intentRoute;

        // Modo auto: usa fast para charla/tareas simples; sube a principal cuando hay RAG o consulta exigente.
        if (isAutoRequest(trimmed)) {
            boolean isSmallTalk = route == ChatPromptSignals.IntentRoute.SMALL_TALK;
            boolean isFactualTech = route == ChatPromptSignals.IntentRoute.FACTUAL_TECH;
            boolean isTextRender = route == ChatPromptSignals.IntentRoute.TEXT_RENDER;
            boolean requiresChatModel = isFactualTech
                    || isTextRender
                    || hasRagContext
                    || (!isSmallTalk && (complexQuery || multiStepQuery));
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

    /**
     * Devuelve el modelo a usar para el puente visual, ignorando alias de chat final.
     */
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

    /**
     * Devuelve el modelo de generación de imagen.
     * Si no hay uno dedicado configurado, cae al visual-model para entornos simples.
     */
    public String resolveImageModel(String requested) {
        String imageModel = normalize(properties.getImageModel());
        String visualModel = normalize(properties.getVisualModel());
        String trimmed = requested == null ? "" : requested.trim();
        String fallback = firstNonBlank(imageModel, visualModel, null);

        if (fallback == null) {
            throw new IllegalArgumentException("No hay modelo de imagen configurado.");
        }
        if (trimmed.isEmpty() || IMAGE_ALIAS.equalsIgnoreCase(trimmed) || DEFAULT_ALIAS.equalsIgnoreCase(trimmed)) {
            return fallback;
        }
        if (imageModel != null && imageModel.equalsIgnoreCase(trimmed)) {
            return imageModel;
        }
        if (visualModel != null && visualModel.equalsIgnoreCase(trimmed)) {
            return fallback;
        }
        return fallback;
    }

    public static boolean isImageGenerationRequest(String requested) {
        return IMAGE_ALIAS.equalsIgnoreCase(requested == null ? "" : requested.trim());
    }

    /**
     * Devuelve el modelo liviano usado por el response guard.
     */
    public String resolveResponseGuardModel() {
        return normalize(properties.getResponseGuardModel());
    }

    /**
     * Devuelve el modelo principal de chat configurado, con fallback al rapido si hace falta.
     */
    public String resolvePrimaryChatModel() {
        String defaultModel = normalize(properties.getChatModel());
        String fastModel = normalize(properties.getFastChatModel());
        return firstNonBlank(defaultModel, fastModel, null);
    }

    /**
     * Indica si el modelo recibido coincide con el principal del sistema.
     */
    public boolean isPrimaryChatModel(String model) {
        String primary = resolvePrimaryChatModel();
        return primary != null && primary.equalsIgnoreCase(normalize(model));
    }

    /**
     * Devuelve el primer valor no vacio de una lista de candidatos.
     */
    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    /**
     * Normaliza nombres de modelo removiendo espacios y convirtiendo vacios en null.
     */
    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Detecta si el cliente delego el routing de modelo al backend.
     */
    private static boolean isAutoRequest(String requested) {
        return requested == null
                || requested.isBlank()
                || DEFAULT_ALIAS.equalsIgnoreCase(requested)
                || AUTO_ALIAS.equalsIgnoreCase(requested);
    }
}


