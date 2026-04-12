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
 * Router de proceso del chat.
 *
 * Contrato de rutas:
 * - CHAT
 * - RAG
 * - ACTION
 * - IMAGE_GENERATE
 * - IMAGE_EXTRACT
 * - MIXED_PIPELINE
 *
 * Politica:
 * - Hard rules primero.
 * - Heuristica segundo.
 * - Clasificador LLM solo para casos ambiguos.
 * - Fallback seguro si no hay confianza suficiente.
 */
@Service
public class ChatProcessRouter {

    private static final Logger log = LoggerFactory.getLogger(ChatProcessRouter.class);

    private static final Pattern IMAGE_ACTION_HINTS = Pattern.compile(
            "\\b(genera(?:r)?|crea(?:r)?|dibuja(?:r)?|pinta(?:r)?|ilustra(?:r)?|renderiza(?:r)?|haz(?:me)?\\s+(?:una\\s+)?imagen|dame\\s+(?:una\\s+)?(?:imagen|foto|ilustracion|render)|muestrame\\s+(?:una\\s+)?(?:imagen|foto|ilustracion|render)|quiero\\s+(?:una\\s+)?(?:imagen|foto|ilustracion|render)|quiero\\s+que\\s+(?:me\\s+)?(?:des|hagas|generes|crees|dibujes|pongas|muestres)\\s+(?:una\\s+)?(?:imagen|foto|fotografia|ilustracion|render)|(?:puedes|podrias|podes)\\s+(?:hacerme|crearme|generarme|darme|dibujarme|pintarme)\\s+(?:una\\s+)?(?:imagen|foto|ilustracion|render)|show\\s+me\\s+(?:an?\\s+)?(?:image|picture|photo)|make\\s+(?:an\\s+)?image|generate\\s+(?:an\\s+)?image|text\\s*to\\s*image|txt2img|img2img)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern IMAGE_SUBJECT_HINTS = Pattern.compile(
            "\\b(imagen|foto|fotografia|photography|picture|image|wallpaper|poster|portada|logo|icono|avatar|retrato|ilustracion|render|escena|mockup)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern IMAGE_EDIT_HINTS = Pattern.compile(
            "\\b(basad[oa]\\s+en|a\\s+partir\\s+de|con\\s+esta\\s+imagen|usa(?:ndo)?\\s+esta\\s+imagen|transforma(?:r)?|edita(?:r)?|retoca(?:r)?|mejora(?:r)?|mejor\\w*|convierte(?:r)?|cambia(?:r)?|modifica(?:r)?|corrige(?:r)?|arregla(?:r)?|anade(?:r)?|añade(?:r)?|agrega(?:r)?|quita(?:r)?|elimina(?:r)?|version|variacion|estilo|style|stylize|inpaint|outpaint)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern IMAGE_ANALYSIS_HINTS = Pattern.compile(
            "\\b(analiza(?:r)?|describe(?:r)?|que\\s+ves|explica(?:r)?|resume(?:r)?|extrae(?:r)?\\s+texto|ocr|clasifica(?:r)?|detecta(?:r)?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern VISION_EXTRACT_HINTS = Pattern.compile(
            "\\b(tabla|excel|csv|extrae(?:r)?|extraer|transcribe(?:r)?|transcripcion|leer|lectura|ocr|campos?|lista|numeros?|valores?|datos|pasar\\s+a|convertir|json|estructura|estructurado|dame\\s+estos\\s+datos)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern ACTION_HINTS = Pattern.compile(
            "\\b(envia(?:r)?|manda(?:r)?|borra(?:r)?|elimina(?:r)?|crea(?:r)?\\s+t(?:i|\\u00ed)cket|publica(?:r)?|haz\\s+request|guarda(?:r)?|descarga(?:r)?|sube(?:r)?|ejecuta(?:r)?|llama(?:r)?\\s+api|actualiza(?:r)?|modifica(?:r)?|programa(?:r)?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern HOME_AUTOMATION_HINTS = Pattern.compile(
            "\\b(domotica|dom\\u00f3tica|casa|hogar|luces?|lamparas?|termostato|calefaccion|aire\\s+acondicionado|persianas?|cerradura|puerta\\s+principal|garaje|alarma|riego|camara(?:s)?|enchufe(?:s)?\\s+inteligentes?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern HOME_AUTOMATION_ACTION_HINTS = Pattern.compile(
            "\\b(controla(?:r)?|enciende(?:r)?|apaga(?:r)?|abre(?:r)?|cierra(?:r)?|sube(?:r)?|baja(?:r)?|activa(?:r)?|desactiva(?:r)?|ajusta(?:r)?|bloquea(?:r)?|desbloquea(?:r)?|programa(?:r)?\\s+escena|modo\\s+ausente|modo\\s+noche)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern AUTONOMOUS_ACTION_HINTS = Pattern.compile(
            "\\b(decide\\s+tu|decide\\s+por\\s+mi|sin\\s+preguntar|automaticamente|autonom(?:a|o|amente)|toma\\s+decisiones|actua\\s+solo)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern TABLE_HINTS = Pattern.compile(
            "\\b(tabla|excel|csv|columnas?|filas?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern JSON_HINTS = Pattern.compile(
            "\\b(json|objeto\\s+json|estructura\\s+json|formato\\s+json)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern MIXED_HINTS = Pattern.compile(
            "\\b(y\\s+luego|despues|a\\s+continuacion|then|and\\s+then|primero|segundo|paso\\s+1|paso\\s+2|combina|mezcla|ademas)\\b",
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

    private static final Pattern IMAGE_IMPERATIVE_HINTS = Pattern.compile(
            "\\b(mejor\\w*|anade(?:r)?|añade(?:r)?|agrega(?:r)?|quita(?:r)?|elimina(?:r)?|cambia(?:r)?|modifica(?:r)?|retoca(?:r)?|corrige(?:r)?|arregla(?:r)?|mas\\s+realista|más\\s+realista|otra\\s+pata|otra\\s+patita)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    // Indicadores de salida tipo documento/texto: bloquean el path "genera sin sujeto imagen"
    private static final Pattern TEXT_DOCUMENT_HINTS = Pattern.compile(
            "\\b(informe|reporte|documento|texto|email|correo|carta|resumen|lista|codigo|script|funcion|clase|sql|consulta|query|formula|expresion|algoritmo|plantilla|template|borrador|ensayo|articulo|post|tweet|mensaje|nota)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final double SAFE_FALLBACK_CONFIDENCE = 0.60;

    private final ChatModelSelector modelSelector;
    private final OllamaClient ollamaClient;
    private final ChatProcessRouterProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private RouterFeedbackStore feedbackStore;

    public ChatProcessRouter(ChatModelSelector modelSelector,
                             OllamaClient ollamaClient,
                             ChatProcessRouterProperties properties) {
        this.modelSelector = modelSelector;
        this.ollamaClient = ollamaClient;
        this.properties = properties;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFeedbackStore(RouterFeedbackStore feedbackStore) {
        this.feedbackStore = feedbackStore;
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
        PromptSignals signals = analyzePrompt(normalizedPrompt, mediaFlags);

        if (ChatModelSelector.isImageGenerationRequest(requested)) {
            return new ProcessDecision(
                    ProcessRoute.IMAGE_GENERATE,
                    "requested-model",
                    1.0,
                    "Modelo de imagen solicitado explicitamente",
                    false,
                    mediaFlags.hasImageMedia() ? PipelineHint.IMAGE_IMG2IMG : PipelineHint.IMAGE_TXT2IMG,
                    ChatModelSelector.IMAGE_ALIAS,
                    false,
                    false,
                    "image"
            );
        }

        if (!properties.isEnabled() || !isAutoRequest(requested)) {
            ProcessRoute route = classifyManualRoute(signals, mediaFlags);
            PipelineHint pipeline = pipelineForRoute(route, normalizedPrompt, mediaFlags);
            return enforceGuardrails(new ProcessDecision(
                    route,
                    "requested-model",
                    1.0,
                    "Modelo solicitado o auto-router desactivado",
                    false,
                    pipeline,
                    requested,
                    signals.ragDecision().enabled() || route == ProcessRoute.RAG || route == ProcessRoute.MIXED_PIPELINE,
                    route == ProcessRoute.ACTION || route == ProcessRoute.MIXED_PIPELINE,
                    expectedOutputFromSignals(signals)
            ), normalizedPrompt, signals, mediaFlags);
        }

        ProcessDecision hardRuleDecision = assessHardRules(signals, mediaFlags);
        if (hardRuleDecision != null) {
            return enforceGuardrails(hardRuleDecision, normalizedPrompt, signals, mediaFlags);
        }

        HeuristicAssessment heuristic = assessHeuristically(normalizedPrompt, signals, mediaFlags);

        // Consulta el historial de errores antes de usar la decision heuristica.
        // Si hay correcciones previas para prompts similares, las aplica con alta confianza.
        ProcessDecision feedbackDecision = applyFeedbackCorrection(normalizedPrompt, heuristic, mediaFlags, signals);
        if (feedbackDecision != null) {
            return enforceGuardrails(feedbackDecision, normalizedPrompt, signals, mediaFlags);
        }

        ProcessDecision heuristicDecision = new ProcessDecision(
                heuristic.route(),
                "heuristic",
                heuristic.confidence(),
                heuristic.reason(),
                false,
                heuristic.pipeline(),
                heuristic.recommendedModelAlias(),
                heuristic.needsRag(),
                heuristic.needsAction(),
                heuristic.expectedOutput()
        );

        if (!shouldUseLlmAssessment(normalizedPrompt, heuristic, mediaFlags)) {
            return enforceGuardrails(heuristicDecision, normalizedPrompt, signals, mediaFlags);
        }

        ProcessDecision llmDecision = assessWithSmallModel(normalizedPrompt, signals, mediaFlags, heuristic);
        if (llmDecision == null) {
            return enforceGuardrails(heuristicDecision, normalizedPrompt, signals, mediaFlags);
        }

        double llmThreshold = clamp01(properties.getLlmConfidenceThreshold());
        if (llmDecision.confidence() < llmThreshold) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "process_router_llm_discarded route={} confidence={} threshold={} reason={}",
                        llmDecision.route(),
                        formatConfidence(llmDecision.confidence()),
                        formatConfidence(llmThreshold),
                        llmDecision.reason()
                );
            }
            return safeFallbackDecision(mediaFlags, signals, "llm-low-confidence");
        }

        return enforceGuardrails(llmDecision, normalizedPrompt, signals, mediaFlags);
    }

    /**
     * Hard rules ejecutadas antes de heuristica y LLM.
     */
    private ProcessDecision assessHardRules(PromptSignals signals, MediaFlags mediaFlags) {
        if (mediaFlags.hasImageMedia() && signals.hasExtractIntent()
                && (signals.hasImageGenerateIntent() || signals.hasActionIntent() || signals.hasMixedHint())) {
            return new ProcessDecision(
                    ProcessRoute.MIXED_PIPELINE,
                    "hard-rule",
                    0.99,
                    "Imagen con extraccion y segunda etapa de accion/generacion",
                    false,
                    PipelineHint.MIXED_EXTRACT_THEN_ACTION,
                    ChatModelSelector.VISUAL_ALIAS,
                    true,
                    true,
                    signals.hasImageGenerateIntent() ? "image" : expectedOutputFromSignals(signals)
            );
        }

        if (mediaFlags.hasImageMedia() && signals.hasExtractIntent()) {
            return new ProcessDecision(
                    ProcessRoute.IMAGE_EXTRACT,
                    "hard-rule",
                    0.99,
                    "Imagen con solicitud de extraer datos/OCR/tabla",
                    false,
                    PipelineHint.VISION_EXTRACT,
                    ChatModelSelector.VISUAL_ALIAS,
                    false,
                    false,
                    expectedOutputFromSignals(signals)
            );
        }

        if (mediaFlags.hasImageMedia() && signals.hasImageGenerateIntent()) {
            return new ProcessDecision(
                    ProcessRoute.IMAGE_GENERATE,
                    "hard-rule",
                    0.99,
                    "Imagen de referencia para generar variante",
                    false,
                    PipelineHint.IMAGE_IMG2IMG,
                    ChatModelSelector.IMAGE_ALIAS,
                    false,
                    false,
                    "image"
            );
        }

        // Imagen con intencion clara de analisis: describe, explica, que ves, etc.
        if (mediaFlags.hasImageMedia() && signals.hasImageAnalysisIntent() && !signals.hasImageGenerateIntent()) {
            return new ProcessDecision(
                    ProcessRoute.CHAT,
                    "hard-rule",
                    0.97,
                    "Analisis visual explicito solicitado",
                    false,
                    PipelineHint.VISION_ANALYZE,
                    ChatModelSelector.VISUAL_ALIAS,
                    false,
                    false,
                    "text"
            );
        }

        // Imagen con pregunta generica: por defecto analizar el contenido visual
        if (mediaFlags.hasImageMedia() && signals.isQuestionLike()
                && !signals.hasExtractIntent() && !signals.hasImageGenerateIntent()) {
            return new ProcessDecision(
                    ProcessRoute.CHAT,
                    "hard-rule",
                    0.92,
                    "Imagen con pregunta: analisis visual por defecto",
                    false,
                    PipelineHint.VISION_ANALYZE,
                    ChatModelSelector.VISUAL_ALIAS,
                    false,
                    false,
                    "text"
            );
        }

        if (!mediaFlags.hasImageMedia() && signals.hasImageGenerateIntent()) {
            return new ProcessDecision(
                    ProcessRoute.IMAGE_GENERATE,
                    "hard-rule",
                    0.96,
                    "Solicitud textual explicita de generar imagen",
                    false,
                    PipelineHint.IMAGE_TXT2IMG,
                    ChatModelSelector.IMAGE_ALIAS,
                    false,
                    false,
                    "image"
            );
        }

        if (signals.hasHomeAutomationHint() && (signals.hasActionIntent() || signals.hasAutonomyHint())) {
            return new ProcessDecision(
                    ProcessRoute.ACTION,
                    "hard-rule",
                    0.94,
                    "Solicitud de control domotico detectada",
                    false,
                    PipelineHint.ACTION_EXECUTION,
                    ChatModelSelector.CHAT_ALIAS,
                    false,
                    true,
                    "text"
            );
        }

        if (signals.hasActionIntent() && signals.ragDecision().enabled()) {
            return new ProcessDecision(
                    ProcessRoute.MIXED_PIPELINE,
                    "hard-rule",
                    0.93,
                    "Accion con dependencia de contexto interno",
                    false,
                    PipelineHint.MIXED_RAG_THEN_ACTION,
                    ChatModelSelector.CHAT_ALIAS,
                    true,
                    true,
                    "json"
            );
        }

        if (signals.hasActionIntent()) {
            return new ProcessDecision(
                    ProcessRoute.ACTION,
                    "hard-rule",
                    0.90,
                    "Solicitud operativa detectada",
                    false,
                    PipelineHint.ACTION_EXECUTION,
                    ChatModelSelector.CHAT_ALIAS,
                    false,
                    true,
                    "json"
            );
        }

        return null;
    }

    private HeuristicAssessment assessHeuristically(String normalizedPrompt,
                                                    PromptSignals signals,
                                                    MediaFlags mediaFlags) {
        if (normalizedPrompt.isBlank()) {
            return new HeuristicAssessment(
                    ProcessRoute.CHAT,
                    0.95,
                    false,
                    "Prompt vacio",
                    PipelineHint.CHAT_FAST,
                    ChatModelSelector.FAST_ALIAS,
                    false,
                    false,
                    "text"
            );
        }

        if (mediaFlags.hasImageMedia() && signals.hasExtractIntent()) {
            return new HeuristicAssessment(
                    ProcessRoute.IMAGE_EXTRACT,
                    0.94,
                    false,
                    "Extraccion estructurada desde imagen",
                    PipelineHint.VISION_EXTRACT,
                    ChatModelSelector.VISUAL_ALIAS,
                    false,
                    false,
                    expectedOutputFromSignals(signals)
            );
        }

        if (mediaFlags.hasImageMedia() && signals.hasImageAnalysisIntent() && !signals.hasImageGenerateIntent()) {
            return new HeuristicAssessment(
                    ProcessRoute.CHAT,
                    0.93,
                    false,
                    "Analisis visual no generativo",
                    PipelineHint.VISION_ANALYZE,
                    ChatModelSelector.VISUAL_ALIAS,
                    false,
                    false,
                    "text"
            );
        }

        if (mediaFlags.hasImageMedia() && signals.hasImageGenerateIntent()) {
            return new HeuristicAssessment(
                    ProcessRoute.IMAGE_GENERATE,
                    0.97,
                    false,
                    "Imagen de referencia para img2img",
                    PipelineHint.IMAGE_IMG2IMG,
                    ChatModelSelector.IMAGE_ALIAS,
                    false,
                    false,
                    "image"
            );
        }

        if (mediaFlags.hasImageMedia() && signals.hasMixedHint()) {
            return new HeuristicAssessment(
                    ProcessRoute.MIXED_PIPELINE,
                    0.68,
                    true,
                    "Imagen con posible flujo multi-etapa",
                    PipelineHint.MIXED_EXTRACT_THEN_ACTION,
                    ChatModelSelector.VISUAL_ALIAS,
                    signals.ragDecision().enabled(),
                    signals.hasActionIntent(),
                    expectedOutputFromSignals(signals)
            );
        }

        if (mediaFlags.hasImageMedia()) {
            // Fallback seguro: imagen sin intencion clara → analizar el contenido visual.
            // IMAGE_EXTRACT solo se activa con intencion explicita (OCR, tabla, datos).
            return new HeuristicAssessment(
                    ProcessRoute.CHAT,
                    0.88,
                    false,
                    "Adjunto visual sin intencion especifica: analisis visual por defecto",
                    PipelineHint.VISION_ANALYZE,
                    ChatModelSelector.VISUAL_ALIAS,
                    false,
                    false,
                    "text"
            );
        }

        if (signals.hasImageGenerateIntent()) {
            return new HeuristicAssessment(
                    ProcessRoute.IMAGE_GENERATE,
                    0.90,
                    false,
                    "Solicitud textual de generar imagen",
                    PipelineHint.IMAGE_TXT2IMG,
                    ChatModelSelector.IMAGE_ALIAS,
                    false,
                    false,
                    "image"
            );
        }

        if (signals.hasActionIntent() && signals.ragDecision().enabled()) {
            return new HeuristicAssessment(
                    ProcessRoute.MIXED_PIPELINE,
                    0.84,
                    true,
                    "Accion con necesidad de contexto",
                    PipelineHint.MIXED_RAG_THEN_ACTION,
                    ChatModelSelector.CHAT_ALIAS,
                    true,
                    true,
                    "json"
            );
        }

        if (signals.hasActionIntent()) {
            return new HeuristicAssessment(
                    ProcessRoute.ACTION,
                    0.82,
                    false,
                    "Solicitud operativa",
                    PipelineHint.ACTION_EXECUTION,
                    ChatModelSelector.CHAT_ALIAS,
                    false,
                    true,
                    "json"
            );
        }

        if (signals.ragDecision().enabled()) {
            return new HeuristicAssessment(
                    ProcessRoute.RAG,
                    0.88,
                    false,
                    "Consulta dependiente de conocimiento interno",
                    PipelineHint.CHAT_RAG,
                    ChatModelSelector.CHAT_ALIAS,
                    true,
                    false,
                    "text"
            );
        }

        PipelineHint pipeline = classifyChatPipeline(normalizedPrompt, mediaFlags);
        return new HeuristicAssessment(
                ProcessRoute.CHAT,
                signals.isQuestionLike() ? 0.88 : 0.80,
                !signals.isQuestionLike(),
                "Chat por defecto",
                pipeline,
                recommendedAliasForPipeline(pipeline),
                false,
                false,
                expectedOutputFromSignals(signals)
        );
    }

    private boolean shouldUseLlmAssessment(String normalizedPrompt,
                                           HeuristicAssessment heuristic,
                                           MediaFlags mediaFlags) {
        if (!properties.isLlmAssessmentEnabled()) {
            return false;
        }
        // Los turnos con imagen tienen rutas deterministas (hard rules + heuristica).
        // No se necesita LLM: eliminar la latencia adicional completamente.
        if (mediaFlags.hasImageMedia()) {
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
        if ((heuristic.route() == ProcessRoute.IMAGE_GENERATE || heuristic.route() == ProcessRoute.IMAGE_EXTRACT)
                && heuristic.confidence() >= clamp01(properties.getHeuristicImageThreshold())) {
            return false;
        }
        if (heuristic.route() == ProcessRoute.CHAT && heuristic.confidence() >= 0.90) {
            return false;
        }
        return true;
    }

    private ProcessDecision assessWithSmallModel(String normalizedPrompt,
                                                 PromptSignals signals,
                                                 MediaFlags mediaFlags,
                                                 HeuristicAssessment heuristic) {
        try {
            String fastModel = modelSelector.resolveChatModel(ChatModelSelector.FAST_ALIAS);
            String raw = ollamaClient.chat(
                    List.of(
                            new OllamaClient.Message("system", buildSystemPrompt()),
                            new OllamaClient.Message("user", buildUserPrompt(normalizedPrompt, signals, mediaFlags, heuristic))
                    ),
                    fastModel
            );
            JsonNode payload = parseJsonPayload(raw);
            ProcessRoute route = parseRoute(textOr(payload, "route", "CHAT"));
            double confidence = clamp01(payload.path("confidence").asDouble(0.0));
            boolean needsRag = boolOr(payload, "needs_rag", "needsRag", signals.ragDecision().enabled());
            boolean needsAction = boolOr(payload, "needs_action", "needsAction", signals.hasActionIntent());
            String expectedOutput = normalizeExpectedOutput(textOr(
                    payload,
                    "expected_output",
                    expectedOutputFromSignals(signals)
            ));
            String reason = textOr(payload, "reason", "llm-sin-razon");

            if (route == heuristic.route()) {
                return new ProcessDecision(
                        route,
                        "llm-fast",
                        confidence,
                        reason,
                        true,
                        heuristic.pipeline(),
                        heuristic.recommendedModelAlias(),
                        needsRag || heuristic.needsRag(),
                        needsAction || heuristic.needsAction(),
                        expectedOutput
                );
            }

            PipelineHint pipeline = pipelineForRoute(route, normalizedPrompt, mediaFlags);
            String alias = recommendedAliasForRoute(route, pipeline, mediaFlags);
            return new ProcessDecision(
                    route,
                    "llm-fast",
                    confidence,
                    reason,
                    true,
                    pipeline,
                    alias,
                    needsRag,
                    needsAction,
                    expectedOutput
            );
        } catch (Exception ex) {
            log.warn("process_router_llm_failed cause={}", safe(ex.getMessage()));
            return null;
        }
    }

    private String buildSystemPrompt() {
        return """
                Eres un router de backend.
                Tu trabajo es decidir SOLO la ruta de proceso.

                Devuelve SOLO JSON valido con este esquema exacto:
                {"route":"CHAT|RAG|ACTION|IMAGE_GENERATE|IMAGE_EXTRACT|MIXED_PIPELINE","confidence":0.0,"needs_rag":false,"needs_action":false,"expected_output":"text|table|json|image","reason":"breve"}

                Reglas:
                - Si hay imagen y el usuario pide tabla/OCR/extraer/transcribir: IMAGE_EXTRACT.
                - Si hay imagen y pide crear otra imagen/estilo/transformar: IMAGE_GENERATE.
                - Si hay mezcla de extraer + accion o extraer + generar: MIXED_PIPELINE.
                - Nunca uses IMAGE_GENERATE si expected_output es table/json/text.
                - Si hay duda, usa ruta segura: IMAGE_EXTRACT con imagen, CHAT sin imagen.
                - Sin markdown, sin texto extra.
                """;
    }

    private String buildUserPrompt(String normalizedPrompt,
                                   PromptSignals signals,
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
                - hint_extract: %s
                - hint_image_generate: %s
                - hint_action: %s
                - hint_rag: %s
                - expected_output_hint: %s
                Devuelve SOLO JSON.
                """.formatted(
                prompt,
                mediaFlags.hasImageMedia(),
                mediaFlags.hasDocumentMedia(),
                heuristic.route().name(),
                formatConfidence(heuristic.confidence()),
                heuristic.reason(),
                signals.hasExtractIntent(),
                signals.hasImageGenerateIntent(),
                signals.hasActionIntent(),
                signals.ragDecision().enabled(),
                expectedOutputFromSignals(signals)
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

    private boolean boolOr(JsonNode node, String fieldA, String fieldB, boolean fallback) {
        if (node == null) {
            return fallback;
        }
        if (node.has(fieldA)) {
            return node.path(fieldA).asBoolean(fallback);
        }
        if (node.has(fieldB)) {
            return node.path(fieldB).asBoolean(fallback);
        }
        return fallback;
    }

    private ProcessRoute parseRoute(String routeValue) {
        if (routeValue == null || routeValue.isBlank()) {
            return ProcessRoute.CHAT;
        }
        String normalized = routeValue.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "RAG" -> ProcessRoute.RAG;
            case "ACTION" -> ProcessRoute.ACTION;
            case "IMAGE_GENERATE", "IMAGE", "IMAGE_GEN" -> ProcessRoute.IMAGE_GENERATE;
            case "IMAGE_EXTRACT", "VISION_EXTRACT", "OCR" -> ProcessRoute.IMAGE_EXTRACT;
            case "MIXED_PIPELINE", "MIXED", "PIPELINE" -> ProcessRoute.MIXED_PIPELINE;
            default -> ProcessRoute.CHAT;
        };
    }

    private String normalizeExpectedOutput(String value) {
        if (value == null || value.isBlank()) {
            return "text";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "table", "json", "image" -> normalized;
            default -> "text";
        };
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

    private PromptSignals analyzePrompt(String normalizedPrompt, MediaFlags mediaFlags) {
        String text = normalizedPrompt == null ? "" : normalizedPrompt.toLowerCase(Locale.ROOT);
        boolean hasActionHint = IMAGE_ACTION_HINTS.matcher(text).find();
        boolean hasSubjectHint = IMAGE_SUBJECT_HINTS.matcher(text).find();
        boolean hasEditHint = IMAGE_EDIT_HINTS.matcher(text).find();
        boolean hasExtractHint = VISION_EXTRACT_HINTS.matcher(text).find();
        boolean hasAnalysisHint = IMAGE_ANALYSIS_HINTS.matcher(text).find();
        boolean hasHomeAutomationHint = HOME_AUTOMATION_HINTS.matcher(text).find();
        boolean hasHomeActionHint = HOME_AUTOMATION_ACTION_HINTS.matcher(text).find();
        boolean hasAutonomyHint = AUTONOMOUS_ACTION_HINTS.matcher(text).find();
        boolean hasOperationalActionHint = ACTION_HINTS.matcher(text).find()
                || (hasHomeAutomationHint && (hasHomeActionHint || hasAutonomyHint));
        boolean technicalDebug = TECHNICAL_DEBUG_HINTS.matcher(text).find();
        boolean promptStyle = PROMPT_STYLE_HINTS.matcher(text).find();
        boolean questionLike = isQuestionLike(text);
        boolean mixedHint = MIXED_HINTS.matcher(text).find();
        boolean tableHint = TABLE_HINTS.matcher(text).find();
        boolean jsonHint = JSON_HINTS.matcher(text).find();
        boolean hasImperativeImageHint = IMAGE_IMPERATIVE_HINTS.matcher(text).find();
        boolean hasTextDocumentHint = TEXT_DOCUMENT_HINTS.matcher(text).find();

        // Caso 1: verbo de accion/edicion + sujeto imagen explicito (imagen, foto, render, etc.)
        boolean imageByExplicitSubject = (hasActionHint || hasEditHint || (mediaFlags.hasImageMedia() && hasImperativeImageHint))
                && (hasSubjectHint || hasEditHint || promptStyle || hasImperativeImageHint || mediaFlags.hasImageMedia());
        // Caso 2: "genera/crea un gato" sin "imagen" pero sin senales de documento/texto/extraccion/pregunta
        boolean imageByVerbAlone = hasActionHint && !hasSubjectHint && !hasExtractHint
                && !questionLike && !technicalDebug && !hasTextDocumentHint && !tableHint && !jsonHint;
        // Caso 3: sujeto imagen + estilo fotografico (prompt puramente descriptivo txt2img)
        boolean imageByStyle = !mediaFlags.hasImageMedia() && hasSubjectHint && promptStyle && !questionLike && !technicalDebug;

        boolean imageGenerateIntent = imageByExplicitSubject || imageByVerbAlone || imageByStyle;

        ChatPromptSignals.RagDecision ragDecision = ChatPromptSignals.ragDecision(normalizedPrompt, mediaFlags.hasDocumentMedia());
        return new PromptSignals(
                imageGenerateIntent,
                hasExtractHint,
                hasAnalysisHint,
                hasOperationalActionHint,
                technicalDebug,
                questionLike,
                mixedHint,
                tableHint,
                jsonHint,
                promptStyle,
                hasHomeAutomationHint,
                hasAutonomyHint,
                ragDecision
        );
    }

    private boolean isQuestionLike(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("?") || QUESTION_PREFIX_HINTS.matcher(text).find();
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

    private ProcessRoute classifyManualRoute(PromptSignals signals, MediaFlags mediaFlags) {
        if (mediaFlags.hasImageMedia() && signals.hasExtractIntent()) {
            return ProcessRoute.IMAGE_EXTRACT;
        }
        if (signals.hasActionIntent() && signals.ragDecision().enabled()) {
            return ProcessRoute.MIXED_PIPELINE;
        }
        if (signals.hasActionIntent()) {
            return ProcessRoute.ACTION;
        }
        if (signals.ragDecision().enabled()) {
            return ProcessRoute.RAG;
        }
        return ProcessRoute.CHAT;
    }

    private PipelineHint pipelineForRoute(ProcessRoute route,
                                          String normalizedPrompt,
                                          MediaFlags mediaFlags) {
        if (route == null) {
            return PipelineHint.CHAT_FAST;
        }
        return switch (route) {
            case IMAGE_GENERATE -> mediaFlags.hasImageMedia()
                    ? PipelineHint.IMAGE_IMG2IMG
                    : PipelineHint.IMAGE_TXT2IMG;
            case IMAGE_EXTRACT -> VISION_EXTRACT_HINTS.matcher(normalizedPrompt.toLowerCase(Locale.ROOT)).find()
                    ? PipelineHint.VISION_EXTRACT
                    : PipelineHint.VISION_ANALYZE;
            case RAG -> PipelineHint.CHAT_RAG;
            case ACTION -> PipelineHint.ACTION_EXECUTION;
            case MIXED_PIPELINE -> mediaFlags.hasImageMedia()
                    ? PipelineHint.MIXED_EXTRACT_THEN_ACTION
                    : PipelineHint.MIXED_RAG_THEN_ACTION;
            case CHAT -> classifyChatPipeline(normalizedPrompt, mediaFlags);
        };
    }

    /**
     * Consulta el feedback store para ver si hay correcciones conocidas para este prompt.
     * Devuelve una decision corregida si hay evidencia suficiente, null si no hay datos o feedback desactivado.
     */
    private ProcessDecision applyFeedbackCorrection(String normalizedPrompt,
                                                    HeuristicAssessment heuristic,
                                                    MediaFlags mediaFlags,
                                                    PromptSignals signals) {
        if (feedbackStore == null) {
            return null;
        }
        String suggestedRoute = feedbackStore.suggestCorrection(normalizedPrompt);
        if (suggestedRoute == null) {
            return null;
        }
        ProcessRoute correctedRoute;
        try {
            correctedRoute = ProcessRoute.valueOf(suggestedRoute);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        if (correctedRoute == heuristic.route()) {
            return null;  // ya coincide, no hace falta correccion
        }

        PipelineHint pipeline = pipelineForRoute(correctedRoute, normalizedPrompt, mediaFlags);
        String alias = recommendedAliasForRoute(correctedRoute, pipeline, mediaFlags);
        boolean needsRag = correctedRoute == ProcessRoute.RAG || correctedRoute == ProcessRoute.MIXED_PIPELINE
                || signals.ragDecision().enabled();
        boolean needsAction = correctedRoute == ProcessRoute.ACTION || correctedRoute == ProcessRoute.MIXED_PIPELINE;
        String expectedOutput = correctedRoute == ProcessRoute.IMAGE_GENERATE ? "image" : expectedOutputFromSignals(signals);

        log.info(
                "process_router_feedback_applied heuristic={} corrected={} alias={}",
                heuristic.route(),
                correctedRoute,
                alias
        );
        return new ProcessDecision(
                correctedRoute,
                "feedback",
                Math.max(heuristic.confidence(), 0.88),
                "feedback-correction:heuristic=" + heuristic.route(),
                false,
                pipeline,
                alias,
                needsRag,
                needsAction,
                expectedOutput
        );
    }

    private ProcessDecision safeFallbackDecision(MediaFlags mediaFlags,
                                                 PromptSignals signals,
                                                 String reason) {
        if (mediaFlags.hasImageMedia()) {
            return new ProcessDecision(
                    ProcessRoute.CHAT,
                    "fallback",
                    SAFE_FALLBACK_CONFIDENCE,
                    reason,
                    false,
                    PipelineHint.VISION_ANALYZE,
                    ChatModelSelector.VISUAL_ALIAS,
                    false,
                    false,
                    "text"
            );
        }
        return new ProcessDecision(
                ProcessRoute.CHAT,
                "fallback",
                SAFE_FALLBACK_CONFIDENCE,
                reason,
                false,
                PipelineHint.CHAT_FAST,
                ChatModelSelector.FAST_ALIAS,
                false,
                false,
                "text"
        );
    }

    private ProcessDecision enforceGuardrails(ProcessDecision candidate,
                                              String normalizedPrompt,
                                              PromptSignals signals,
                                              MediaFlags mediaFlags) {
        if (candidate == null) {
            return safeFallbackDecision(mediaFlags, signals, "guardrail-null");
        }

        String expectedOutput = normalizeExpectedOutput(candidate.expectedOutput());
        boolean nonImageOutput = "table".equals(expectedOutput)
                || "json".equals(expectedOutput)
                || "text".equals(expectedOutput);

        if (candidate.route() == ProcessRoute.IMAGE_GENERATE && nonImageOutput) {
            if (mediaFlags.hasImageMedia()) {
                return new ProcessDecision(
                        ProcessRoute.IMAGE_EXTRACT,
                        "guardrail",
                        Math.max(candidate.confidence(), 0.90),
                        "expected_output_no_es_image",
                        candidate.usedLlm(),
                        PipelineHint.VISION_EXTRACT,
                        ChatModelSelector.VISUAL_ALIAS,
                        candidate.needsRag(),
                        candidate.needsAction(),
                        expectedOutput
                );
            }
            PipelineHint pipeline = classifyChatPipeline(normalizedPrompt, mediaFlags);
            return new ProcessDecision(
                    ProcessRoute.CHAT,
                    "guardrail",
                    Math.max(candidate.confidence(), 0.90),
                    "expected_output_no_es_image",
                    candidate.usedLlm(),
                    pipeline,
                    recommendedAliasForPipeline(pipeline),
                    candidate.needsRag(),
                    candidate.needsAction(),
                    expectedOutput
            );
        }

        if (candidate.route() == ProcessRoute.IMAGE_EXTRACT && !mediaFlags.hasImageMedia()) {
            PipelineHint pipeline = classifyChatPipeline(normalizedPrompt, mediaFlags);
            return new ProcessDecision(
                    ProcessRoute.CHAT,
                    "guardrail",
                    Math.max(candidate.confidence(), 0.88),
                    "image_extract_requires_image_media",
                    candidate.usedLlm(),
                    pipeline,
                    recommendedAliasForPipeline(pipeline),
                    candidate.needsRag(),
                    candidate.needsAction(),
                    expectedOutput
            );
        }

        if (candidate.route() == ProcessRoute.ACTION && candidate.needsRag()) {
            return new ProcessDecision(
                    ProcessRoute.MIXED_PIPELINE,
                    "guardrail",
                    candidate.confidence(),
                    "action_requires_context",
                    candidate.usedLlm(),
                    PipelineHint.MIXED_RAG_THEN_ACTION,
                    ChatModelSelector.CHAT_ALIAS,
                    true,
                    true,
                    expectedOutput
            );
        }

        PipelineHint pipeline = candidate.pipeline() == null
                ? pipelineForRoute(candidate.route(), normalizedPrompt, mediaFlags)
                : candidate.pipeline();
        String alias = hasText(candidate.recommendedModelAlias())
                ? candidate.recommendedModelAlias()
                : recommendedAliasForRoute(candidate.route(), pipeline, mediaFlags);

        return new ProcessDecision(
                candidate.route(),
                candidate.source(),
                candidate.confidence(),
                candidate.reason(),
                candidate.usedLlm(),
                pipeline,
                alias,
                candidate.needsRag(),
                candidate.needsAction(),
                expectedOutput
        );
    }

    private String expectedOutputFromSignals(PromptSignals signals) {
        if (signals == null) {
            return "text";
        }
        if (signals.hasTableHint()) {
            return "table";
        }
        if (signals.hasJsonHint()) {
            return "json";
        }
        if (signals.hasImageGenerateIntent()) {
            return "image";
        }
        return "text";
    }

    /**
     * Traduce el pipeline sugerido a un alias de modelo soportado por el backend.
     */
    private String recommendedAliasForPipeline(PipelineHint pipeline) {
        if (pipeline == null) {
            return ChatModelSelector.AUTO_ALIAS;
        }
        return switch (pipeline) {
            case IMAGE_TXT2IMG, IMAGE_IMG2IMG -> ChatModelSelector.IMAGE_ALIAS;
            case VISION_EXTRACT, VISION_ANALYZE -> ChatModelSelector.VISUAL_ALIAS;
            case CHAT_COMPLEX, CHAT_RAG, ACTION_EXECUTION, MIXED_EXTRACT_THEN_ACTION, MIXED_EXTRACT_THEN_RAG,
                    MIXED_RAG_THEN_ACTION -> ChatModelSelector.CHAT_ALIAS;
            case CHAT_FAST -> ChatModelSelector.FAST_ALIAS;
        };
    }

    private String recommendedAliasForRoute(ProcessRoute route, PipelineHint pipeline, MediaFlags mediaFlags) {
        if (route == null) {
            return recommendedAliasForPipeline(pipeline);
        }
        return switch (route) {
            case IMAGE_GENERATE -> ChatModelSelector.IMAGE_ALIAS;
            case IMAGE_EXTRACT -> ChatModelSelector.VISUAL_ALIAS;
            case MIXED_PIPELINE -> mediaFlags.hasImageMedia()
                    ? ChatModelSelector.VISUAL_ALIAS
                    : ChatModelSelector.CHAT_ALIAS;
            case RAG, ACTION -> ChatModelSelector.CHAT_ALIAS;
            case CHAT -> recommendedAliasForPipeline(pipeline);
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Rutas de proceso disponibles para el router.
     */
    public enum ProcessRoute {
        CHAT,
        RAG,
        ACTION,
        IMAGE_GENERATE,
        IMAGE_EXTRACT,
        MIXED_PIPELINE
    }

    /**
     * Pipeline operativo sugerido para ejecucion y trazabilidad.
     */
    public enum PipelineHint {
        IMAGE_TXT2IMG,
        IMAGE_IMG2IMG,
        VISION_EXTRACT,
        VISION_ANALYZE,
        CHAT_RAG,
        CHAT_COMPLEX,
        CHAT_FAST,
        ACTION_EXECUTION,
        MIXED_EXTRACT_THEN_ACTION,
        MIXED_EXTRACT_THEN_RAG,
        MIXED_RAG_THEN_ACTION
    }

    /**
     * Resultado final del router con metadata de ejecucion.
     */
    public record ProcessDecision(ProcessRoute route,
                                  String source,
                                  double confidence,
                                  String reason,
                                  boolean usedLlm,
                                  PipelineHint pipeline,
                                  String recommendedModelAlias,
                                  boolean needsRag,
                                  boolean needsAction,
                                  String expectedOutput) {
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
            expectedOutput = normalizeExpectedOutputValue(expectedOutput);
        }

        private static String normalizeExpectedOutputValue(String value) {
            if (value == null || value.isBlank()) {
                return "text";
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "table", "json", "image" -> normalized;
                default -> "text";
            };
        }
    }

    private record HeuristicAssessment(ProcessRoute route,
                                       double confidence,
                                       boolean ambiguous,
                                       String reason,
                                       PipelineHint pipeline,
                                       String recommendedModelAlias,
                                       boolean needsRag,
                                       boolean needsAction,
                                       String expectedOutput) {
    }

    private record MediaFlags(boolean hasImageMedia, boolean hasDocumentMedia) {
    }

    private record PromptSignals(boolean hasImageGenerateIntent,
                                 boolean hasExtractIntent,
                                 boolean hasImageAnalysisIntent,
                                 boolean hasActionIntent,
                                 boolean hasTechnicalDebugHint,
                                 boolean isQuestionLike,
                                 boolean hasMixedHint,
                                 boolean hasTableHint,
                                 boolean hasJsonHint,
                                 boolean hasPromptStyleHint,
                                 boolean hasHomeAutomationHint,
                                 boolean hasAutonomyHint,
                                 ChatPromptSignals.RagDecision ragDecision) {
    }
}
