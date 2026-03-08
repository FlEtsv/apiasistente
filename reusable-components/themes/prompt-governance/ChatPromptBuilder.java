package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.entity.ChatMessage;
import com.example.apiasistente.chat.service.ChatModelSelector;
import com.example.apiasistente.chat.service.ChatPromptSignals;
import com.example.apiasistente.shared.ai.OllamaClient;
import com.example.apiasistente.rag.service.RagService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Construye los bloques de prompt usados por retrieval y generacion final.
 * Mantiene separada la logica textual para que modificar instrucciones no afecte otras capas.
 */
@Component
public class ChatPromptBuilder {

    private static final int MAX_RETRIEVAL_MEDIA_SNIPPET = 420;

    private final ChatModelSelector modelSelector;

    public ChatPromptBuilder(ChatModelSelector modelSelector) {
        this.modelSelector = modelSelector;
    }

    /**
     * Construye la consulta de retrieval combinando turno actual, contexto reciente y adjuntos textuales.
     */
    public String buildRetrievalQuery(String userText,
                                      List<ChatMessage> recentUserTurns,
                                      List<ChatMediaService.PreparedMedia> media) {
        String current = collapseSpaces(userText);
        if (current.isBlank()) {
            return "";
        }

        StringBuilder sb = new StringBuilder(current.length() + 640);
        sb.append(current);

        // Solo agrega turnos previos del usuario para enriquecer retrieval sin meter respuestas del asistente.
        if (recentUserTurns != null && recentUserTurns.size() > 1) {
            sb.append("\n\nContexto reciente del usuario:\n");
            for (int i = recentUserTurns.size() - 1; i >= 1; i--) {
                String previous = collapseSpaces(recentUserTurns.get(i).getContent());
                if (previous.isBlank()) {
                    continue;
                }
                if (previous.length() > 220) {
                    previous = previous.substring(0, 220);
                }
                sb.append("- ").append(previous).append('\n');
            }
        }

        // Si hay texto extraido de adjuntos, se inyecta como pista adicional para recuperar mejor contexto.
        if (media != null && !media.isEmpty()) {
            boolean hasDocument = media.stream().anyMatch(item -> hasText(item.documentText()));
            if (hasDocument) {
                sb.append("\nContenido adjunto:\n");
                for (ChatMediaService.PreparedMedia item : media) {
                    if (!hasText(item.documentText())) {
                        continue;
                    }
                    String snippet = collapseSpaces(item.documentText());
                    if (snippet.length() > MAX_RETRIEVAL_MEDIA_SNIPPET) {
                        snippet = snippet.substring(0, MAX_RETRIEVAL_MEDIA_SNIPPET);
                    }
                    sb.append("- ").append(snippet).append('\n');
                }
            }
        }

        return sb.toString();
    }

    /**
     * Inserta el historial reciente al prompt en orden conversacional.
     */
    public void appendRecentHistory(List<OllamaClient.Message> target, List<ChatMessage> recentHistory) {
        if (target == null || recentHistory == null || recentHistory.isEmpty()) {
            return;
        }

        for (int i = recentHistory.size() - 1; i >= 0; i--) {
            ChatMessage message = recentHistory.get(i);
            target.add(new OllamaClient.Message(
                    message.getRole() == ChatMessage.Role.USER ? "user" : "assistant",
                    message.getContent()
            ));
        }
    }

    /**
     * Construye el bloque de usuario para turnos con RAG, incluyendo fuentes y reglas de citacion.
     */
    public String buildRagBlock(String userText,
                                List<RagService.ScoredChunk> scored,
                                String visualBridge,
                                List<ChatMediaService.PreparedMedia> media,
                                String fallbackMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("Fuentes RAG (ordenadas por relevancia):\n");

        // Enumera las fuentes con IDs estables [S#] para que luego puedan citarse y validarse.
        if (scored != null && !scored.isEmpty()) {
            for (int i = 0; i < scored.size(); i++) {
                RagService.ScoredChunk scoredChunk = scored.get(i);
                sb.append("\n[S").append(i + 1).append("] ")
                        .append("doc=\"").append(scoredChunk.chunk().getDocument().getTitle()).append("\" ")
                        .append("(chunk ").append(scoredChunk.chunk().getChunkIndex()).append(") ")
                        .append("score=").append(String.format(Locale.US, "%.3f", scoredChunk.score()))
                        .append("\n")
                        .append(scoredChunk.effectiveText())
                        .append("\n");
            }
        } else {
            sb.append("(sin contexto relevante)\n");
        }

        // El puente visual/documental permite sumar observaciones de imagen sin alterar la estructura base del prompt.
        if (hasText(visualBridge)) {
            sb.append("\n---\n");
            sb.append("Contexto visual/documental intermedio (Qwen-VL):\n");
            sb.append(visualBridge).append("\n");
        }

        appendMediaSection(sb, media, "\nAdjuntos recibidos:\n");

        sb.append("\n---\n");
        sb.append("Instrucciones:\n");
        sb.append("- Marco base: identifica objetivo, restricciones y formato esperado antes de responder.\n");
        sb.append("- Responde usando SOLO estos fragmentos comprimidos del RAG.\n");
        sb.append("- Cada afirmacion factual debe incluir cita [S#].\n");
        sb.append("- Si no hay suficiente respaldo, responde exactamente: \"").append(fallbackMessage).append("\".\n");
        sb.append("- No inventes, no asumas y no completes huecos.\n");
        sb.append("- Si piden mejoras de codigo/sistema, prioriza cambios concretos con riesgo y beneficio.\n");
        sb.append("- No afirmes ejecuciones o cambios reales que no consten en el contexto.\n");
        sb.append("- Responde en castellano.\n");
        sb.append("- Formatea en Markdown con titulos y subtitulos claros.\n");
        sb.append("- Si incluyes codigo, usa bloques con triple backticks y lenguaje (ej: ```java).\n\n");
        sb.append("Pregunta del usuario: ").append(userText);

        return sb.toString();
    }

    /**
     * Construye el bloque de usuario para chat libre sin grounding obligatorio.
     */
    public String buildChatBlock(String userText,
                                 String visualBridge,
                                 List<ChatMediaService.PreparedMedia> media,
                                 boolean taskCompletionMode,
                                 boolean textRenderMode) {
        StringBuilder sb = new StringBuilder();

        // El puente visual se usa como contexto resumido para no enviar imagenes al modelo final si no hace falta.
        if (hasText(visualBridge)) {
            sb.append("Contexto visual/documental intermedio:\n");
            sb.append(visualBridge).append("\n\n");
        }

        appendMediaSection(sb, media, "Adjuntos recibidos:\n");
        if (media != null && !media.isEmpty()) {
            sb.append('\n');
        }

        sb.append("Instrucciones:\n");
        sb.append("- Marco base: primero detecta objetivo, limites y formato de salida solicitado.\n");
        sb.append("- Responde en castellano, de forma directa y util.\n");
        sb.append("- Si el usuario pide crear, redactar, resolver, planificar o transformar algo, entrega el resultado completo en esta misma respuesta.\n");
        sb.append("- Si el pedido es tecnico/codigo, propone mejoras accionables (problema -> cambio -> impacto).\n");
        sb.append("- Usa el historial reciente para resolver referencias breves como \"mas grande\", \"asi\", \"con caracteres\" o \"sin emoji\".\n");
        sb.append("- Si el usuario corrige formato, tamano o estilo de algo ya mencionado, aplica la correccion directamente.\n");
        sb.append("- Solo haz UNA pregunta breve si, incluso usando el historial reciente, sigue siendo imposible saber que ejecutar.\n");
        sb.append("- Evita inventar datos concretos.\n");
        sb.append("- No afirmes despliegues, tests o cambios en codigo si no se han ejecutado realmente.\n");
        sb.append("- Mantiene un tono natural y conciso.\n\n");
        if (taskCompletionMode) {
            sb.append("Modo ejecucion directa:\n");
            sb.append("- Devuelve el resultado final directamente, sin pedir confirmacion.\n");
            sb.append("- No cierres con preguntas del tipo \"te gusta?\" o \"quieres que...\".\n");
            sb.append("- Si el usuario pide una correccion, conserva el objeto del historial reciente y aplica solo ese cambio.\n\n");
        }
        if (textRenderMode) {
            sb.append("Modo dibujo/texto:\n");
            sb.append("- Si el usuario pide caracteres, ASCII o texto, usa solo caracteres en un bloque monoespaciado.\n");
            sb.append("- No uses emojis ni placeholders visuales.\n");
            sb.append("- Si el usuario pide una version mas grande, devuelve una version claramente mas grande del mismo motivo.\n\n");
        }
        sb.append("Mensaje del usuario: ").append(userText);
        return sb.toString();
    }

    /**
     * Selecciona el modelo final considerando preferencia del cliente y caracteristicas del turno.
     */
    public String selectChatModel(String requestedModel,
                                  boolean hasRagContext,
                                  boolean complexQuery,
                                  boolean multiStepQuery,
                                  ChatPromptSignals.IntentRoute intentRoute,
                                  boolean directExecutionMode) {
        // En modo de ejecucion directa conviene priorizar el modelo principal para reducir respuestas tibias.
        if (directExecutionMode && isAutoRequestedModel(requestedModel)) {
            String primaryModel = resolvePrimaryChatModel();
            if (hasText(primaryModel)) {
                return primaryModel;
            }
        }
        return modelSelector.resolveChatModel(
                requestedModel,
                hasRagContext,
                complexQuery,
                multiStepQuery,
                intentRoute
        );
    }

    /**
     * Devuelve el modelo principal configurado para respuestas completas.
     */
    public String resolvePrimaryChatModel() {
        return modelSelector.resolvePrimaryChatModel();
    }

    /**
     * Describe adjuntos ya normalizados dentro del prompt.
     */
    private void appendMediaSection(StringBuilder sb,
                                    List<ChatMediaService.PreparedMedia> media,
                                    String header) {
        if (sb == null || media == null || media.isEmpty()) {
            return;
        }

        sb.append(header);
        for (ChatMediaService.PreparedMedia item : media) {
            sb.append("- ")
                    .append(item.name())
                    .append(" [")
                    .append(item.mimeType())
                    .append("]");
            if (hasText(item.documentText())) {
                sb.append(" (con texto extraido)");
            }
            if (hasText(item.imageBase64())) {
                sb.append(" (imagen)");
            }
            sb.append('\n');
        }
    }

    /**
     * Detecta si el cliente pidio routing automatico de modelo.
     */
    private boolean isAutoRequestedModel(String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            return true;
        }
        String normalized = requestedModel.trim();
        return ChatModelSelector.DEFAULT_ALIAS.equalsIgnoreCase(normalized)
                || ChatModelSelector.AUTO_ALIAS.equalsIgnoreCase(normalized);
    }

    /**
     * Ayuda local para validar texto util.
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Colapsa espacios para que las queries y prompts sean mas compactos.
     */
    private String collapseSpaces(String value) {
        if (!hasText(value)) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }
}


