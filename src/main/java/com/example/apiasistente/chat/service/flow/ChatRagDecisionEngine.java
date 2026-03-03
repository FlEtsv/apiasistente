package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.service.ChatModelSelector;
import com.example.apiasistente.chat.service.ChatPromptSignals;
import com.example.apiasistente.chat.service.ChatTurnPlanner;
import com.example.apiasistente.shared.ai.OllamaClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Pattern;

/**
 * Motor de decisión RAG.
 *
 * Mezcla tres capas:
 * - Heurísticas baratas del planner actual.
 * - Autoevaluación con modelo ligero para preguntas ambiguas o técnicas.
 * - Verificación posterior cuando se respondió sin RAG y conviene auditar la decisión.
 *
 * No intenta sustituir el planner ni el gate de metadata; los complementa.
 */
@Service
public class ChatRagDecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(ChatRagDecisionEngine.class);

    private static final Pattern PERSONAL_HINTS = Pattern.compile(
            "\\b(mi|mis|nuestro|nuestra|nuestros|nuestras|interno|interna|privado|privada|usuario|cuenta|sesion|proyecto|base|documento|documentacion|log|logs|endpoint|ruta|api\\s*key|monitor)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern REALTIME_HINTS = Pattern.compile(
            "\\b(hoy|ayer|ahora|actual|actualizado|ultima|ultimo|minuto|minutos|tiempo real|noticias|esta semana|este mes|202[4-9]|203\\d)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern TECHNICAL_HINTS = Pattern.compile(
            "\\b(java|spring|docker|sql|cache|latencia|api|endpoint|trace|stack|monitor|prometheus|grafana|embedding|chunk|retrieval|rerank|rag|oauth|jwt|vector|hnsw|scraper)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private final OllamaClient ollama;
    private final ChatModelSelector modelSelector;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, CachedLlmAssessment> cache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> cacheOrder = new ConcurrentLinkedDeque<>();

    @Value("${chat.rag-decision.enabled:true}")
    private boolean decisionEnabled;

    @Value("${chat.rag-decision.llm-assessment-enabled:true}")
    private boolean llmAssessmentEnabled;

    @Value("${chat.rag-decision.verify-no-rag-answers:true}")
    private boolean verifyNoRagAnswers;

    @Value("${chat.rag-decision.heuristic-confidence-threshold:0.72}")
    private double heuristicConfidenceThreshold;

    @Value("${chat.rag-decision.self-confidence-threshold:0.70}")
    private double selfConfidenceThreshold;

    @Value("${chat.rag-decision.technical-confidence-threshold:0.80}")
    private double technicalConfidenceThreshold;

    @Value("${chat.rag-decision.verify-answer-confidence-threshold:0.74}")
    private double verifyAnswerConfidenceThreshold;

    @Value("${chat.rag-decision.min-answer-chars:140}")
    private int minAnswerChars;

    @Value("${chat.rag-decision.cache-ttl-ms:3600000}")
    private long cacheTtlMs;

    @Value("${chat.rag-decision.max-cache-entries:500}")
    private int maxCacheEntries;

    public ChatRagDecisionEngine(OllamaClient ollama, ChatModelSelector modelSelector) {
        this.ollama = ollama;
        this.modelSelector = modelSelector;
    }

    /**
     * Decide si la consulta parece necesitar contexto externo antes de pagar retrieval.
     */
    public DecisionAssessment assessQuery(String userText,
                                          ChatTurnPlanner.TurnPlan turnPlan,
                                          boolean hasDocumentMedia) {
        DecisionAssessment heuristicAssessment = buildHeuristicAssessment(userText, turnPlan, hasDocumentMedia);
        if (!decisionEnabled || !shouldUseLlmAssessment(heuristicAssessment, turnPlan, hasDocumentMedia)) {
            return heuristicAssessment;
        }

        LlmAssessment llmAssessment = loadOrRunAssessment(userText, turnPlan, hasDocumentMedia);
        if (llmAssessment == null) {
            return heuristicAssessment;
        }

        return mergeAssessments(heuristicAssessment, llmAssessment, turnPlan);
    }

    /**
     * Audita respuestas directas para reintentar con RAG cuando la decisión inicial fue demasiado optimista.
     */
    public AnswerVerification verifyDirectAnswer(String userText,
                                                 String assistantText,
                                                 ChatTurnPlanner.TurnPlan turnPlan) {
        if (!decisionEnabled || !verifyNoRagAnswers) {
            return AnswerVerification.skip("post-check-disabled");
        }
        if (!hasText(userText) || !hasText(assistantText)) {
            return AnswerVerification.skip("post-check-empty");
        }
        if (assistantText.trim().length() < Math.max(40, minAnswerChars)) {
            return AnswerVerification.skip("post-check-answer-short");
        }

        ChatTurnPlanner.TurnPlan plan = turnPlan == null
                ? new ChatTurnPlanner.TurnPlan(
                ChatPromptSignals.IntentRoute.TASK_SIMPLE,
                false,
                ChatTurnPlanner.ReasoningLevel.MEDIUM,
                false,
                false,
                0.50
        )
                : turnPlan;

        if (plan.intentRoute() == ChatPromptSignals.IntentRoute.SMALL_TALK
                || plan.intentRoute() == ChatPromptSignals.IntentRoute.TEXT_RENDER) {
            return AnswerVerification.skip("post-check-non-factual");
        }

        if (plan.confidence() >= clamp01(verifyAnswerConfidenceThreshold)
                && !plan.ragDecision().enabled()
                && !plan.complexQuery()
                && !plan.multiStepQuery()) {
            return AnswerVerification.skip("post-check-heuristic-confident");
        }

        try {
            String raw = ollama.chat(
                    List.of(
                            new OllamaClient.Message("system", buildAnswerVerificationSystemPrompt()),
                            new OllamaClient.Message("user", buildAnswerVerificationUserPrompt(userText, assistantText, plan))
                    ),
                    resolveDecisionModel()
            );
            JsonNode node = parseJsonPayload(raw);
            boolean retryWithRag = node.path("retry_with_rag").asBoolean(false);
            double confidence = clamp01(node.path("confidence").asDouble(0.0));
            String reason = textOr(node, "reason", retryWithRag ? "post-check-rag" : "post-check-ok");

            if (!retryWithRag && confidence < verifyAnswerConfidenceThreshold) {
                retryWithRag = true;
                reason = appendReason(reason, "confianza-baja");
            }

            return new AnswerVerification(
                    retryWithRag,
                    confidence,
                    reason,
                    true
            );
        } catch (Exception e) {
            log.warn("No se pudo verificar la respuesta directa para relanzar con RAG: {}", e.getMessage());
            return AnswerVerification.skip("post-check-error");
        }
    }

    private DecisionAssessment buildHeuristicAssessment(String userText,
                                                        ChatTurnPlanner.TurnPlan turnPlan,
                                                        boolean hasDocumentMedia) {
        ChatTurnPlanner.TurnPlan plan = turnPlan == null
                ? new ChatTurnPlanner.TurnPlan(
                ChatPromptSignals.IntentRoute.TASK_SIMPLE,
                false,
                ChatTurnPlanner.ReasoningLevel.MEDIUM,
                false,
                false,
                0.50
        )
                : turnPlan;

        QueryType queryType = classifyQueryType(userText, plan, hasDocumentMedia);
        boolean heuristicNeedsRag = hasDocumentMedia
                || plan.ragDecision().requiresEvidence()
                || queryType == QueryType.PERSONAL
                || queryType == QueryType.REALTIME;
        double heuristicConfidence = clamp01(plan.confidence());
        boolean verifyAfterDirectAnswer = !heuristicNeedsRag
                && (queryType == QueryType.TECHNICAL
                || queryType == QueryType.OTHER
                || heuristicConfidence < verifyAnswerConfidenceThreshold);

        return new DecisionAssessment(
                queryType,
                heuristicNeedsRag,
                heuristicNeedsRag,
                heuristicConfidence,
                heuristicConfidence,
                false,
                false,
                verifyAfterDirectAnswer,
                "heuristic",
                buildHeuristicReason(queryType, plan, hasDocumentMedia)
        );
    }

    private LlmAssessment loadOrRunAssessment(String userText,
                                              ChatTurnPlanner.TurnPlan turnPlan,
                                              boolean hasDocumentMedia) {
        String cacheKey = buildCacheKey(userText, turnPlan, hasDocumentMedia);
        CachedLlmAssessment cached = readCache(cacheKey);
        if (cached != null) {
            return cached.assessment().withCacheHit(true);
        }

        if (!llmAssessmentEnabled) {
            return null;
        }

        try {
            String raw = ollama.chat(
                    List.of(
                            new OllamaClient.Message("system", buildPreDecisionSystemPrompt()),
                            new OllamaClient.Message("user", buildPreDecisionUserPrompt(userText, turnPlan, hasDocumentMedia))
                    ),
                    resolveDecisionModel()
            );

            JsonNode node = parseJsonPayload(raw);
            LlmAssessment assessment = new LlmAssessment(
                    QueryType.fromValue(textOr(node, "type", "other")),
                    node.path("needs_external_context").asBoolean(false),
                    clamp01(node.path("confidence").asDouble(0.0)),
                    textOr(node, "reason", "llm-sin-razon"),
                    false
            );
            writeCache(cacheKey, assessment);
            return assessment;
        } catch (Exception e) {
            log.warn("Decision RAG ligera no disponible, se usa solo heurística: {}", e.getMessage());
            return null;
        }
    }

    private DecisionAssessment mergeAssessments(DecisionAssessment heuristic,
                                                LlmAssessment llm,
                                                ChatTurnPlanner.TurnPlan turnPlan) {
        double effectiveConfidence = llm.confidence() > 0.0 ? llm.confidence() : heuristic.confidence();
        boolean lowConfidence = heuristic.queryType() == QueryType.TECHNICAL
                ? effectiveConfidence < technicalConfidenceThreshold
                : effectiveConfidence < selfConfidenceThreshold;

        boolean needsRag = heuristic.needsRag()
                || llm.needsExternalContext()
                || lowConfidence;
        boolean verifyAfterDirectAnswer = !needsRag
                && (heuristic.verifyAfterDirectAnswer()
                || effectiveConfidence < verifyAnswerConfidenceThreshold);

        String source = llm.cacheHit() ? "hybrid-cache" : "hybrid-llm";
        String reason = appendReason(
                heuristic.reason(),
                "llm:type=" + llm.queryType().value()
                        + ",needs_context=" + llm.needsExternalContext()
                        + ",confidence=" + formatDouble(effectiveConfidence)
                        + ",reason=" + llm.reason()
        );

        return new DecisionAssessment(
                llm.queryType() == QueryType.OTHER ? heuristic.queryType() : llm.queryType(),
                needsRag,
                heuristic.needsExternalContext() || llm.needsExternalContext(),
                effectiveConfidence,
                heuristic.heuristicConfidence(),
                true,
                llm.cacheHit(),
                verifyAfterDirectAnswer,
                source,
                reason
        );
    }

    private boolean shouldUseLlmAssessment(DecisionAssessment heuristicAssessment,
                                           ChatTurnPlanner.TurnPlan turnPlan,
                                           boolean hasDocumentMedia) {
        if (hasDocumentMedia) {
            return false;
        }
        if (turnPlan == null) {
            return true;
        }
        if (turnPlan.ragDecision().requiresEvidence()) {
            return false;
        }
        if (turnPlan.intentRoute() == ChatPromptSignals.IntentRoute.SMALL_TALK
                || turnPlan.intentRoute() == ChatPromptSignals.IntentRoute.TEXT_RENDER) {
            return false;
        }
        if (turnPlan.ragDecision().mode() == ChatPromptSignals.RagMode.PREFERRED) {
            return true;
        }
        if (heuristicAssessment.queryType() == QueryType.TECHNICAL
                || heuristicAssessment.queryType() == QueryType.OTHER) {
            return true;
        }
        return turnPlan.confidence() < heuristicConfidenceThreshold;
    }

    private QueryType classifyQueryType(String userText,
                                        ChatTurnPlanner.TurnPlan turnPlan,
                                        boolean hasDocumentMedia) {
        String normalized = normalize(userText);
        if (hasDocumentMedia) {
            return QueryType.PERSONAL;
        }
        if (REALTIME_HINTS.matcher(normalized).find()) {
            return QueryType.REALTIME;
        }
        if (PERSONAL_HINTS.matcher(normalized).find() || (turnPlan != null && turnPlan.ragDecision().requiresEvidence())) {
            return QueryType.PERSONAL;
        }
        if (TECHNICAL_HINTS.matcher(normalized).find()
                || (turnPlan != null && turnPlan.intentRoute() == ChatPromptSignals.IntentRoute.FACTUAL_TECH)) {
            return QueryType.TECHNICAL;
        }
        if (turnPlan != null && turnPlan.intentRoute() == ChatPromptSignals.IntentRoute.SMALL_TALK) {
            return QueryType.GENERAL;
        }
        return normalized.length() < 18 ? QueryType.GENERAL : QueryType.OTHER;
    }

    private String buildHeuristicReason(QueryType queryType,
                                        ChatTurnPlanner.TurnPlan turnPlan,
                                        boolean hasDocumentMedia) {
        if (hasDocumentMedia) {
            return "heuristic:adjunto-documental";
        }
        if (turnPlan == null) {
            return "heuristic:sin-plan";
        }
        return "heuristic:type=" + queryType.value()
                + ",rag_mode=" + turnPlan.ragDecision().mode()
                + ",intent=" + turnPlan.intentRoute()
                + ",confidence=" + formatDouble(turnPlan.confidence())
                + ",reason=" + turnPlan.ragDecision().reason();
    }

    private String resolveDecisionModel() {
        return modelSelector.resolveChatModel(ChatModelSelector.FAST_ALIAS);
    }

    /**
     * Prompt para clasificar tipo de consulta y necesidad de contexto.
     */
    private String buildPreDecisionSystemPrompt() {
        return """
                Eres un motor de decisión RAG. No respondas la pregunta del usuario.
                Clasifica la consulta y di si necesita contexto externo o de la base del proyecto.
                Devuelve SOLO JSON válido con este esquema exacto:
                {
                  "type": "general|personal|realtime|technical|other",
                  "needs_external_context": true,
                  "confidence": 0.0,
                  "reason": "breve"
                }
                Reglas:
                - "personal" cubre datos privados, del proyecto, documentos internos, logs o sistema del usuario.
                - "realtime" cubre actualidad, estados recientes, hoy, ahora o datos frescos.
                - "technical" cubre preguntas técnicas profundas donde el conocimiento interno puede mejorar la precisión.
                - Usa confidence alta solo si la decisión es clara.
                - Sin markdown, sin texto extra, sin comentarios.
                """;
    }

    private String buildPreDecisionUserPrompt(String userText,
                                              ChatTurnPlanner.TurnPlan turnPlan,
                                              boolean hasDocumentMedia) {
        ChatTurnPlanner.TurnPlan plan = Objects.requireNonNullElseGet(turnPlan, () -> new ChatTurnPlanner.TurnPlan(
                ChatPromptSignals.IntentRoute.TASK_SIMPLE,
                false,
                ChatTurnPlanner.ReasoningLevel.MEDIUM,
                false,
                false,
                0.50
        ));
        return """
                Consulta: %s
                Heurística actual:
                - intent_route: %s
                - rag_mode: %s
                - heuristic_confidence: %s
                - complex_query: %s
                - multi_step: %s
                - has_document_media: %s
                Devuelve SOLO el JSON.
                """.formatted(
                userText == null ? "" : userText.trim(),
                plan.intentRoute(),
                plan.ragDecision().mode(),
                formatDouble(plan.confidence()),
                plan.complexQuery(),
                plan.multiStepQuery(),
                hasDocumentMedia
        );
    }

    /**
     * Prompt para auditar respuestas directas y decidir si conviene relanzar con RAG.
     */
    private String buildAnswerVerificationSystemPrompt() {
        return """
                Eres un verificador de seguridad para respuestas sin RAG.
                Decide si la respuesta dada al usuario podría ser incorrecta o incompleta por faltar contexto externo.
                Devuelve SOLO JSON válido con este esquema exacto:
                {
                  "retry_with_rag": true,
                  "confidence": 0.0,
                  "reason": "breve"
                }
                Reglas:
                - Marca retry_with_rag=true si la respuesta necesita base interna, datos privados, datos recientes o validación externa.
                - Marca retry_with_rag=false si la respuesta es suficientemente fiable sin RAG.
                - Sin markdown, sin texto extra.
                """;
    }

    private String buildAnswerVerificationUserPrompt(String userText,
                                                     String assistantText,
                                                     ChatTurnPlanner.TurnPlan turnPlan) {
        return """
                Consulta original: %s
                Respuesta sin RAG: %s
                Plan heurístico:
                - intent_route: %s
                - rag_mode: %s
                - heuristic_confidence: %s
                Evalúa si conviene reintentar con RAG y devuelve SOLO JSON.
                """.formatted(
                userText == null ? "" : userText.trim(),
                assistantText == null ? "" : assistantText.trim(),
                turnPlan.intentRoute(),
                turnPlan.ragDecision().mode(),
                formatDouble(turnPlan.confidence())
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

    private CachedLlmAssessment readCache(String key) {
        if (!hasText(key)) {
            return null;
        }
        CachedLlmAssessment cached = cache.get(key);
        if (cached == null) {
            return null;
        }
        if (cached.expiresAt().isBefore(Instant.now())) {
            cache.remove(key);
            cacheOrder.remove(key);
            return null;
        }
        return cached;
    }

    private void writeCache(String key, LlmAssessment assessment) {
        if (!hasText(key) || assessment == null) {
            return;
        }
        cache.put(key, new CachedLlmAssessment(
                assessment,
                Instant.now().plusMillis(Math.max(5_000L, cacheTtlMs))
        ));
        cacheOrder.remove(key);
        cacheOrder.addLast(key);
        evictCacheIfNeeded();
    }

    private void evictCacheIfNeeded() {
        int maxEntries = Math.max(32, maxCacheEntries);
        while (cache.size() > maxEntries) {
            String oldest = cacheOrder.pollFirst();
            if (oldest == null) {
                return;
            }
            cache.remove(oldest);
        }
    }

    private String buildCacheKey(String userText,
                                 ChatTurnPlanner.TurnPlan turnPlan,
                                 boolean hasDocumentMedia) {
        String normalized = normalize(userText);
        if (normalized.isBlank()) {
            return "";
        }
        String ragMode = turnPlan == null ? "OFF" : turnPlan.ragDecision().mode().name();
        String intent = turnPlan == null ? "TASK_SIMPLE" : turnPlan.intentRoute().name();
        return normalized + "|" + ragMode + "|" + intent + "|" + hasDocumentMedia;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String appendReason(String left, String right) {
        if (!hasText(left)) {
            return right == null ? "" : right.trim();
        }
        if (!hasText(right)) {
            return left.trim();
        }
        return left.trim() + " | " + right.trim();
    }

    private String textOr(JsonNode node, String field, String fallback) {
        if (node == null || field == null) {
            return fallback;
        }
        String value = node.path(field).asText("");
        return hasText(value) ? value.trim() : fallback;
    }

    private String formatDouble(double value) {
        return String.format(Locale.US, "%.3f", clamp01(value));
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public enum QueryType {
        GENERAL("general"),
        PERSONAL("personal"),
        REALTIME("realtime"),
        TECHNICAL("technical"),
        OTHER("other");

        private final String value;

        QueryType(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static QueryType fromValue(String value) {
            if (value == null || value.isBlank()) {
                return OTHER;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (QueryType type : values()) {
                if (type.value.equals(normalized)) {
                    return type;
                }
            }
            return OTHER;
        }
    }

    public record DecisionAssessment(QueryType queryType,
                                     boolean needsRag,
                                     boolean needsExternalContext,
                                     double confidence,
                                     double heuristicConfidence,
                                     boolean usedLlm,
                                     boolean cacheHit,
                                     boolean verifyAfterDirectAnswer,
                                     String source,
                                     String reason) {

        public DecisionAssessment {
            queryType = queryType == null ? QueryType.OTHER : queryType;
            confidence = normalizeConfidence(confidence);
            heuristicConfidence = normalizeConfidence(heuristicConfidence);
            source = source == null ? "heuristic" : source.trim();
            reason = reason == null ? "" : reason.trim();
        }

        private static double normalizeConfidence(double value) {
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
    }

    public record AnswerVerification(boolean retryWithRag,
                                     double confidence,
                                     String reason,
                                     boolean reviewed) {

        public AnswerVerification {
            confidence = normalizeVerificationConfidence(confidence);
            reason = reason == null ? "" : reason.trim();
        }

        public static AnswerVerification skip(String reason) {
            return new AnswerVerification(false, 0.0, reason, false);
        }

        private static double normalizeVerificationConfidence(double value) {
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
    }

    private record LlmAssessment(QueryType queryType,
                                 boolean needsExternalContext,
                                 double confidence,
                                 String reason,
                                 boolean cacheHit) {

        private LlmAssessment withCacheHit(boolean value) {
            return new LlmAssessment(queryType, needsExternalContext, confidence, reason, value);
        }
    }

    private record CachedLlmAssessment(LlmAssessment assessment, Instant expiresAt) {
    }
}
