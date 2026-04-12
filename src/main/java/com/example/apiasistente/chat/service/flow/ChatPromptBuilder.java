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
                                ChatPromptSignals.IntentProfile intentProfile,
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
        sb.append("- Responde usando SOLO el contenido de los fragmentos anteriores.\n");
        sb.append("- Cita cada afirmacion factual con [S#] referenciando el fragmento correspondiente.\n");
        sb.append("- Si los fragmentos no contienen informacion suficiente para responder, tu respuesta debe ser EXACTAMENTE esta frase y nada mas: \"").append(fallbackMessage).append("\"\n");
        sb.append("- No inventes, no asumas y no rellenes huecos con conocimiento externo.\n");
        sb.append("- No afirmes ejecuciones o cambios reales que no consten en el contexto.\n");
        sb.append("- Responde en castellano de forma directa y sin estructuras artificiales.\n");
        sb.append("- Usa Markdown solo si la respuesta es larga o tecnica; evita encabezados para respuestas breves.\n");
        sb.append("- Si incluyes codigo, usa bloques con triple backticks y el lenguaje (ej: ```java).\n\n");
        appendIntentGuidance(sb, intentProfile, true);
        sb.append("Pregunta: ").append(userText);

        return sb.toString();
    }

    /**
     * Construye el bloque de usuario para chat libre sin grounding obligatorio.
     */
    public String buildChatBlock(String userText,
                                 String visualBridge,
                                 List<ChatMediaService.PreparedMedia> media,
                                 ChatPromptSignals.IntentProfile intentProfile,
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

        sb.append("Responde en castellano de forma natural y concisa.\n");
        sb.append("- Si el usuario pide crear, redactar, resolver, planificar o transformar algo, entrega el resultado completo directamente.\n");
        sb.append("- Si el pedido es tecnico o de codigo, propone mejoras accionables (problema -> cambio -> impacto).\n");
        sb.append("- Usa el historial reciente para resolver referencias como \"mas grande\", \"asi\", \"sin emoji\".\n");
        sb.append("- Si el usuario corrige formato, tamano o estilo de algo anterior, aplica solo ese cambio.\n");
        sb.append("- Haz una pregunta breve solo si es imposible saber que ejecutar incluso con el historial.\n");
        sb.append("- No inventes datos concretos ni afirmes cambios que no se hayan ejecutado.\n\n");
        appendIntentGuidance(sb, intentProfile, false);
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

    private void appendIntentGuidance(StringBuilder sb,
                                      ChatPromptSignals.IntentProfile intentProfile,
                                      boolean ragMode) {
        if (sb == null || intentProfile == null) {
            return;
        }

        sb.append("Perfil de intencion:\n");
        sb.append("- Categoria detectada: ")
                .append(intentProfile.category().name())
                .append(".\n");
        sb.append("- Nivel de detalle esperado: ")
                .append(intentProfile.responseStyle().name())
                .append(".\n");

        switch (intentProfile.responseStyle()) {
            case BRIEF -> {
                sb.append("- Responde en 3-6 lineas como maximo salvo que el usuario pida ampliar.\n");
                sb.append("- Evita introducciones y cierres redundantes.\n");
            }
            case DETAILED -> {
                sb.append("- Entrega respuesta estructurada, con pasos accionables y riesgos.\n");
                sb.append("- Prioriza completitud tecnica sobre brevedad.\n");
            }
            default -> {
                sb.append("- Mantiene un tono natural, directo y de longitud media.\n");
                sb.append("- Evita relleno verbal y repeticiones.\n");
            }
        }

        if (intentProfile.homeAutomation()) {
            sb.append("- Domotica detectada: no afirmes que actuaste sobre dispositivos reales si no hubo ejecucion confirmada.\n");
            sb.append("- Ofrece salida en formato operativo: intencion detectada -> accion propuesta -> validacion.\n");
            if (intentProfile.requiresConfirmation()) {
                sb.append("- Requiere confirmacion explicita antes de acciones de riesgo (cerraduras, alarma, accesos, energia).\n");
            }
            if (intentProfile.autonomousDecisionRequested()) {
                sb.append("- El usuario pide autonomia: define limites de seguridad, modo manual de emergencia y registro de decisiones antes de automatizar.\n");
            }
        }

        if (intentProfile.learningRequested()) {
            sb.append("- Si propone aprendizaje continuo, explica feedback loop, metricas y criterios de rollback.\n");
        }
        if (ragMode) {
            sb.append("- Si no hay evidencia suficiente en fuentes RAG, aplica el fallback sin completar con conocimiento externo.\n");
        }
        sb.append('\n');
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


