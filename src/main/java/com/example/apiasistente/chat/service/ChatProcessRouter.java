package com.example.apiasistente.chat.service;

import com.example.apiasistente.chat.config.ChatProcessRouterProperties;
import com.example.apiasistente.chat.dto.ChatMediaInput;
import com.example.apiasistente.shared.ai.OllamaClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Enruta automaticamente un turno a chat normal o generacion de imagen.
 *
 * Estrategia:
 * - Respeta selecciones explicitas del cliente.
 * - En modo auto usa heuristicas rapidas.
 * - Si la heuristica no es concluyente, consulta el modelo rapido para desambiguar.
 */
@Service
public class ChatProcessRouter {

    private static final Logger log = LoggerFactory.getLogger(ChatProcessRouter.class);

    private static final Pattern IMAGE_ACTION_HINTS = Pattern.compile(
            "\\b(genera(?:r)?|crea(?:r)?|dibuja(?:r)?|pinta(?:r)?|ilustra(?:r)?|renderiza(?:r)?|haz(?:me)?\\s+(?:una\\s+)?imagen|make\\s+(?:an\\s+)?image|generate\\s+(?:an\\s+)?image|text\\s*to\\s*image|txt2img|img2img)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern IMAGE_SUBJECT_HINTS = Pattern.compile(
            "\\b(imagen|foto|fotografia|photography|picture|image|wallpaper|poster|portada|logo|icono|avatar|retrato|ilustracion|render|escena|mockup)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern IMAGE_EDIT_HINTS = Pattern.compile(
            "\\b(basad[oa]\\s+en|a\\s+partir\\s+de|con\\s+esta\\s+imagen|usa(?:ndo)?\\s+esta\\s+imagen|transforma(?:r)?|edita(?:r)?|retoca(?:r)?|mejora(?:r)?|convierte(?:r)?|cambia(?:r)?|version|variacion|estilo|style|stylize|inpaint|outpaint)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern IMAGE_ANALYSIS_HINTS = Pattern.compile(
            "\\b(analiza(?:r)?|describe(?:r)?|que\\s+ves|explica(?:r)?|resume(?:r)?|extrae(?:r)?\\s+texto|ocr|clasifica(?:r)?|detecta(?:r)?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern VISION_EXTRACT_HINTS = Pattern.compile(
            "\\b(tabla|extrae(?:r)?|extraer|transcribe(?:r)?|transcripcion|leer|lectura|ocr|campos?|lista|csv|json|estructura|estructurado|datos\\s+en\\s+tabla|dame\\s+estos\\s+datos)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern TECHNICAL_DEBUG_HINTS = Pattern.compile(
            "\\b(error|falla|fallo|puerto|backend|frontend|workflow|comfyui|script|codigo|trazabilidad|rollback|stack\\s*trace|transaction)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern QUESTION_PREFIX_HINTS = Pattern.compile(
            "^(como|que|por\\s+que|cual|cuanto|why|what|how|which)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern PROMPT_STYLE_HINTS = Pattern.compile(
            "\\b(4k|8k|ultra\\s*detailed|photorealistic|cinematic|bokeh|dramatic\\s+lighting|high\\s+detail|masterpiece)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private final ChatModelSelector modelSelector;
    private final OllamaClient ollamaClient;
    private final ChatProcessRouterProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatProcessRouter(ChatModelSelector modelSelector,
                             OllamaClient ollamaClient,
                             ChatProcessRouterProperties properties) {
        this.modelSelector = modelSelector;
        this.ollamaClient = ollamaClient;
        this.properties = properties;
    }

    /**
     * Decide el proceso final del turno.
     */
    public ProcessDecision decide(String userText,
                                  String requestedModel,
                                  List<ChatMediaInput> media) {
        String requested = normalizeModel(requestedModel);
        String normalizedPrompt = normalizePrompt(userText);
        MediaFlags mediaFlags = mediaFlags(media);

        if (ChatModelSelector.isImageGenerationRequest(requested)) {
            return new ProcessDecision(
                    ProcessRoute.IMAGE,
                    "requested-model",
                    1.0,
                    "Modelo de imagen solicitado explicitamente",
                    false,
                    mediaFlags.hasImageMedia() ? PipelineHint.IMAGE_IMG2IMG : PipelineHint.IMAGE_TXT2IMG,
                    requested
            );
        }

        if (!properties.isEnabled() || !isAutoRequest(requested)) {
            PipelineHint pipeline = classifyChatPipeline(normalizedPrompt, mediaFlags);
            return new ProcessDecision(
                    ProcessRoute.CHAT,
                    "requested-model",
                    1.0,
                    "Modelo de chat solicitado o enrutado automatico desactivado",
                    false,
                    pipeline,
                    requested
            );
        }

        HeuristicAssessment heuristic = assessHeuristically(normalizedPrompt, mediaFlags);
        ProcessDecision heuristicDecision = new ProcessDecision(
                heuristic.route(),
                "heuristic",
                heuristic.confidence(),
                heuristic.reason(),
                false,
                heuristic.pipeline(),
                heuristic.recommendedModelAlias()
        );

        if (!shouldUseLlmAssessment(normalizedPrompt, heuristic, mediaFlags)) {
            return heuristicDecision;
        }

        ProcessDecision llmDecision = assessWithSmallModel(normalizedPrompt, mediaFlags, heuristic);
        if (llmDecision == null) {
            return heuristicDecision;
        }
        if (llmDecision.confidence() < clamp01(properties.getLlmConfidenceThreshold())) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "process_router_llm_discarded route={} confidence={} threshold={} reason={}",
                        llmDecision.route(),
                        formatConfidence(llmDecision.confidence()),
                        formatConfidence(properties.getLlmConfidenceThreshold()),
                        llmDecision.reason()
                );
            }
            return heuristicDecision;
        }
        return llmDecision;
    }

    private HeuristicAssessment assessHeuristically(String normalizedPrompt, MediaFlags mediaFlags) {
        if (normalizedPrompt.isBlank()) {
            return new HeuristicAssessment(
                    ProcessRoute.CHAT,
                    0.95,
                    false,
                    "Prompt vacio",
                    PipelineHint.CHAT_FAST,
                    ChatModelSelector.FAST_ALIAS
            );
        }

        String text = normalizedPrompt.toLowerCase(Locale.ROOT);
        boolean hasActionHint = IMAGE_ACTION_HINTS.matcher(text).find();
        boolean hasSubjectHint = IMAGE_SUBJECT_HINTS.matcher(text).find();
        boolean hasEditHint = IMAGE_EDIT_HINTS.matcher(text).find();
        boolean hasAnalysisHint = IMAGE_ANALYSIS_HINTS.matcher(text).find();
        boolean hasExtractHint = VISION_EXTRACT_HINTS.matcher(text).find();
        boolean technicalDebug = TECHNICAL_DEBUG_HINTS.matcher(text).find();
        boolean questionLike = isQuestionLike(text);
        boolean promptStyle = PROMPT_STYLE_HINTS.matcher(text).find();

        // Regla dura: imagen + extraer/tabla/OCR siempre va por chat visual, nunca por image-gen.
        if (mediaFlags.hasImageMedia() && hasExtractHint) {
            return new HeuristicAssessment(
                    ProcessRoute.CHAT,
                    0.99,
                    false,
                    "Extraccion estructurada desde imagen",
                    PipelineHint.VISION_EXTRACT,
                    ChatModelSelector.CHAT_ALIAS
            );
        }

        if (mediaFlags.hasImageMedia() && hasAnalysisHint && !hasEditHint && !hasActionHint) {
            return new HeuristicAssessment(
                    ProcessRoute.CHAT,
                    0.97,
                    false,
                    "Adjunto visual para analisis",
                    PipelineHint.VISION_ANALYZE,
                    ChatModelSelector.CHAT_ALIAS
            );
        }

        if (mediaFlags.hasImageMedia() && (hasEditHint || hasActionHint)) {
            return new HeuristicAssessment(
                    ProcessRoute.IMAGE,
                    0.97,
                    false,
                    "Imagen de referencia para img2img",
                    PipelineHint.IMAGE_IMG2IMG,
                    ChatModelSelector.IMAGE_ALIAS
            );
        }

        if (mediaFlags.hasImageMedia() && !hasAnalysisHint && !technicalDebug && !questionLike) {
            return new HeuristicAssessment(
                    ProcessRoute.CHAT,
                    0.66,
                    true,
                    "Adjunto visual ambiguo: prioriza analisis seguro",
                    PipelineHint.VISION_ANALYZE,
                    ChatModelSelector.CHAT_ALIAS
            );
        }

        if (technicalDebug && questionLike) {
            return new HeuristicAssessment(
                    ProcessRoute.CHAT,
                    0.94,
                    false,
                    "Consulta tecnica/debug",
                    PipelineHint.CHAT_RAG,
                    ChatModelSelector.CHAT_ALIAS
            );
        }

        if (questionLike && !hasActionHint) {
            PipelineHint pipeline = classifyChatPipeline(normalizedPrompt, mediaFlags);
            return new HeuristicAssessment(
                    ProcessRoute.CHAT,
                    0.90,
                    false,
                    "Consulta explicativa",
                    pipeline,
                    recommendedAliasForPipeline(pipeline)
            );
        }

        if (hasActionHint && hasSubjectHint && !questionLike && !technicalDebug) {
            return new HeuristicAssessment(
                    ProcessRoute.IMAGE,
                    0.93,
                    false,
                    "Solicitud explicita de generar imagen",
                    PipelineHint.IMAGE_TXT2IMG,
                    ChatModelSelector.IMAGE_ALIAS
            );
        }

        if (hasActionHint && promptStyle && !questionLike && !technicalDebug) {
            return new HeuristicAssessment(
                    ProcessRoute.IMAGE,
                    0.74,
                    true,
                    "Accion visual ambigua",
                    PipelineHint.IMAGE_TXT2IMG,
                    ChatModelSelector.IMAGE_ALIAS
            );
        }

        if (hasSubjectHint && promptStyle && !questionLike && !technicalDebug) {
            return new HeuristicAssessment(
                    ProcessRoute.IMAGE,
                    0.68,
                    true,
                    "Prompt visual estilo",
                    PipelineHint.IMAGE_TXT2IMG,
                    ChatModelSelector.IMAGE_ALIAS
            );
        }

        PipelineHint pipeline = classifyChatPipeline(normalizedPrompt, mediaFlags);
        return new HeuristicAssessment(
                ProcessRoute.CHAT,
                0.82,
                true,
                "Chat por defecto",
                pipeline,
                recommendedAliasForPipeline(pipeline)
        );
    }

    private boolean shouldUseLlmAssessment(String normalizedPrompt,
                                           HeuristicAssessment heuristic,
                                           MediaFlags mediaFlags) {
        if (!properties.isLlmAssessmentEnabled()) {
            return false;
        }
        if (mediaFlags.hasDocumentMedia()) {
            return false;
        }
        if (normalizedPrompt.length() < Math.max(8, properties.getMinPromptCharsForLlm())) {
            return false;
        }
        if (!heuristic.ambiguous()) {
            return false;
        }
        if (heuristic.route() == ProcessRoute.IMAGE
                && heuristic.confidence() >= clamp01(properties.getHeuristicImageThreshold())
        ) {
            return false;
        }
        if (heuristic.route() == ProcessRoute.CHAT
                && heuristic.confidence() >= 0.90
        ) {
            return false;
        }
        return true;
    }

    private ProcessDecision assessWithSmallModel(String normalizedPrompt,
                                                 MediaFlags mediaFlags,
                                                 HeuristicAssessment heuristic) {
        try {
            String fastModel = modelSelector.resolveChatModel(ChatModelSelector.FAST_ALIAS);
            String raw = ollamaClient.chat(
                    List.of(
                            new OllamaClient.Message("system", buildSystemPrompt()),
                            new OllamaClient.Message("user", buildUserPrompt(normalizedPrompt, mediaFlags, heuristic))
                    ),
                    fastModel
            );
            JsonNode payload = parseJsonPayload(raw);
            String routeValue = textOr(payload, "route", "chat");
            ProcessRoute route = "image".equalsIgnoreCase(routeValue) ? ProcessRoute.IMAGE : ProcessRoute.CHAT;
            double confidence = clamp01(payload.path("confidence").asDouble(0.0));
            String reason = textOr(payload, "reason", "llm-sin-razon");
            if (route == heuristic.route()) {
                return new ProcessDecision(
                        route,
                        "llm-fast",
                        confidence,
                        reason,
                        true,
                        heuristic.pipeline(),
                        heuristic.recommendedModelAlias()
                );
            }
            PipelineHint pipeline = route == ProcessRoute.IMAGE
                    ? (mediaFlags.hasImageMedia() ? PipelineHint.IMAGE_IMG2IMG : PipelineHint.IMAGE_TXT2IMG)
                    : classifyChatPipeline(normalizedPrompt, mediaFlags);
            return new ProcessDecision(
                    route,
                    "llm-fast",
                    confidence,
                    reason,
                    true,
                    pipeline,
                    recommendedAliasForPipeline(pipeline)
            );
        } catch (Exception ex) {
            log.warn("process_router_llm_failed cause={}", safe(ex.getMessage()));
            return null;
        }
    }

    private String buildSystemPrompt() {
        return """
                Eres un router de backend.
                Tu trabajo es decidir SOLO el proceso:
                - "image": si el usuario quiere generar o transformar una imagen como resultado final.
                - "chat": para cualquier otro caso (explicar, depurar, analizar, consultar, configurar, etc).

                Devuelve SOLO JSON valido con este esquema exacto:
                {"route":"chat|image","confidence":0.0,"reason":"breve"}

                Reglas:
                - Si hay duda entre chat e image, elige "chat".
                - Si el usuario trae una foto para ANALIZAR/DESCRIBIR/EXTRAER TABLA O TEXTO, elige "chat".
                - Si el usuario trae una foto para CREAR OTRA VERSION, elige "image".
                - Sin markdown, sin texto extra.
                """;
    }

    private String buildUserPrompt(String normalizedPrompt,
                                   MediaFlags mediaFlags,
                                   HeuristicAssessment heuristic) {
        int maxChars = Math.max(120, properties.getMaxPromptChars());
        String prompt = normalizedPrompt.length() <= maxChars
                ? normalizedPrompt
                : normalizedPrompt.substring(0, maxChars);
        return """
                Prompt usuario: %s
                Senales:
                - has_image_media: %s
                - has_document_media: %s
                - heuristic_route: %s
                - heuristic_confidence: %s
                - heuristic_reason: %s
                Devuelve SOLO JSON.
                """.formatted(
                prompt,
                mediaFlags.hasImageMedia(),
                mediaFlags.hasDocumentMedia(),
                heuristic.route().name().toLowerCase(Locale.ROOT),
                formatConfidence(heuristic.confidence()),
                heuristic.reason()
        );
    }

    private JsonNode parseJsonPayload(String raw) throws Exception {
        String payload = raw == null ? "" : raw.trim();
        int firstBrace = payload.indexOf('{');
        int lastBrace = payload.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            payload = payload.substring(firstBrace, lastBrace + 1);
        }
        return objectMapper.readTree(payload);
    }

    private String textOr(JsonNode node, String field, String fallback) {
        if (node == null || field == null) {
            return fallback;
        }
        String value = node.path(field).asText("");
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private MediaFlags mediaFlags(List<ChatMediaInput> media) {
        if (media == null || media.isEmpty()) {
            return new MediaFlags(false, false);
        }
        boolean hasImage = false;
        boolean hasDocument = false;
        for (ChatMediaInput item : media) {
            if (item == null) {
                continue;
            }
            String mime = item.getMimeType() == null ? "" : item.getMimeType().trim().toLowerCase(Locale.ROOT);
            if (!mime.isBlank() && mime.startsWith("image/")) {
                hasImage = true;
            } else if (!mime.isBlank()) {
                hasDocument = true;
            } else if (item.getText() != null && !item.getText().isBlank()) {
                hasDocument = true;
            }
        }
        return new MediaFlags(hasImage, hasDocument);
    }

    private boolean isQuestionLike(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("?")
                || QUESTION_PREFIX_HINTS.matcher(text).find();
    }

    private String normalizePrompt(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeModel(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private boolean isAutoRequest(String requestedModel) {
        return requestedModel == null
                || requestedModel.isBlank()
                || ChatModelSelector.AUTO_ALIAS.equalsIgnoreCase(requestedModel)
                || ChatModelSelector.DEFAULT_ALIAS.equalsIgnoreCase(requestedModel);
    }

    /**
     * Clasifica el pipeline interno esperado para turnos de chat no generativos.
     * Orden de prioridad:
     * - Vision extract/analyze si hay imagen adjunta.
     * - Chat con RAG si hay señales de dependencia factual/técnica.
     * - Chat complejo para consultas largas multi-paso o rendering textual.
     * - Chat rápido en el resto.
     */
    private PipelineHint classifyChatPipeline(String normalizedPrompt, MediaFlags mediaFlags) {
        if (mediaFlags.hasImageMedia()) {
            if (VISION_EXTRACT_HINTS.matcher(normalizedPrompt.toLowerCase(Locale.ROOT)).find()) {
                return PipelineHint.VISION_EXTRACT;
            }
            return PipelineHint.VISION_ANALYZE;
        }

        boolean hasDocumentMedia = mediaFlags.hasDocumentMedia();
        ChatPromptSignals.RagDecision ragDecision = ChatPromptSignals.ragDecision(normalizedPrompt, hasDocumentMedia);
        if (ragDecision.enabled()) {
            return PipelineHint.CHAT_RAG;
        }

        boolean complexQuery = ChatPromptSignals.isComplexQuery(normalizedPrompt);
        boolean multiStep = ChatPromptSignals.isMultiStepQuery(normalizedPrompt);
        boolean textRender = ChatPromptSignals.wantsTextRendering(normalizedPrompt);
        if (complexQuery || multiStep || textRender) {
            return PipelineHint.CHAT_COMPLEX;
        }
        return PipelineHint.CHAT_FAST;
    }

    /**
     * Traduce el pipeline sugerido a un alias de modelo que ya entiende el backend.
     */
    private String recommendedAliasForPipeline(PipelineHint pipeline) {
        if (pipeline == null) {
            return ChatModelSelector.AUTO_ALIAS;
        }
        return switch (pipeline) {
            case IMAGE_TXT2IMG, IMAGE_IMG2IMG -> ChatModelSelector.IMAGE_ALIAS;
            case VISION_EXTRACT, VISION_ANALYZE, CHAT_COMPLEX, CHAT_RAG -> ChatModelSelector.CHAT_ALIAS;
            case CHAT_FAST -> ChatModelSelector.FAST_ALIAS;
        };
    }

    private double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private String formatConfidence(double value) {
        return String.format(Locale.US, "%.3f", clamp01(value));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public enum ProcessRoute {
        CHAT,
        IMAGE
    }

    /**
     * Resultado final del router:
     * ruta (chat/image), pipeline sugerido y alias recomendado para ejecución.
     */
    public record ProcessDecision(ProcessRoute route,
                                  String source,
                                  double confidence,
                                  String reason,
                                  boolean usedLlm,
                                  PipelineHint pipeline,
                                  String recommendedModelAlias) {

        public ProcessDecision {
            route = route == null ? ProcessRoute.CHAT : route;
            source = source == null || source.isBlank() ? "heuristic" : source.trim();
            if (!Double.isFinite(confidence)) {
                confidence = 0.0;
            } else if (confidence < 0.0) {
                confidence = 0.0;
            } else if (confidence > 1.0) {
                confidence = 1.0;
            }
            reason = reason == null ? "" : reason.trim();
            pipeline = pipeline == null ? PipelineHint.CHAT_FAST : pipeline;
            recommendedModelAlias = recommendedModelAlias == null ? "" : recommendedModelAlias.trim();
        }
    }

    /**
     * Pipeline operativo sugerido por el router para observabilidad y ejecución estable.
     */
    public enum PipelineHint {
        IMAGE_TXT2IMG,
        IMAGE_IMG2IMG,
        VISION_EXTRACT,
        VISION_ANALYZE,
        CHAT_RAG,
        CHAT_COMPLEX,
        CHAT_FAST
    }

    private record HeuristicAssessment(ProcessRoute route,
                                       double confidence,
                                       boolean ambiguous,
                                       String reason,
                                       PipelineHint pipeline,
                                       String recommendedModelAlias) {
    }

    private record MediaFlags(boolean hasImageMedia, boolean hasDocumentMedia) {
    }
}
