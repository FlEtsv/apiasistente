package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.service.ChatModelSelector;
import com.example.apiasistente.shared.ai.OllamaClient;
import com.example.apiasistente.rag.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Centraliza las reglas de grounding y response guard del flujo.
 * Evalua fuerza del contexto, valida citas y define fallback cuando la respuesta no es segura.
 */
@Service
public class ChatGroundingService {

    private static final Logger log = LoggerFactory.getLogger(ChatGroundingService.class);

    private static final int MAX_GUARD_QUESTION_CHARS = 1_200;
    private static final int MAX_GUARD_ANSWER_CHARS = 10_000;
    private static final int MAX_GUARD_SOURCE_HINTS = 6;
    private static final int STRONG_RAG_MIN_CHUNKS_FLOOR = 2;
    private static final double STRONG_RAG_TOP_SCORE_FLOOR = 0.25;
    private static final double WEAK_RAG_TOP_SCORE_CUTOFF = 0.22;
    private static final Pattern SOURCE_CITATION_PATTERN = Pattern.compile("\\[S(\\d+)]");

    private final OllamaClient ollama;
    private final ChatModelSelector modelSelector;

    @Value("${chat.response-guard.enabled:true}")
    private boolean responseGuardEnabled;

    @Value("${chat.response-guard.strict-mode:false}")
    private boolean responseGuardStrictMode;

    @Value("${chat.grounding.enabled:true}")
    private boolean groundingEnabled;

    @Value("${chat.grounding.min-chunks:2}")
    private int groundingMinChunks;

    @Value("${chat.grounding.min-score:0.25}")
    private double groundingMinScore;

    @Value("${chat.grounding.require-citations:true}")
    private boolean groundingRequireCitations;

    @Value("${chat.grounding.retry-primary-model:true}")
    private boolean groundingRetryPrimaryModel;

    @Value("${chat.grounding.fallback-message:No tengo suficiente contexto para responder con precision. Puedes compartir una frase mas o pegar el fragmento exacto?}")
    private String groundingFallbackMessage;

    @Value("${chat.grounding.no-evidence-message:No encontre evidencia en tu base para responder eso. Puedes compartir una ruta, fecha, log o fragmento del documento?}")
    private String groundingNoEvidenceMessage;

    @Value("${chat.grounding.retrieval-unavailable-message:No puedo consultar la base RAG en este momento. Intentalo de nuevo en unos segundos.}")
    private String groundingRetrievalUnavailableMessage;

    public ChatGroundingService(OllamaClient ollama, ChatModelSelector modelSelector) {
        this.ollama = ollama;
        this.modelSelector = modelSelector;
    }

    /**
     * Devuelve el mensaje de fallback general cuando no hay suficiente respaldo para responder.
     */
    public String fallbackMessage() {
        String configured = groundingFallbackMessage == null ? "" : groundingFallbackMessage.trim();
        if (!configured.isEmpty()) {
            return configured;
        }
        return "No tengo suficiente contexto para responder con precision. Puedes compartir una frase mas o pegar el fragmento exacto?";
    }

    /**
     * Devuelve el mensaje especifico para ausencia total de evidencia en retrieval.
     */
    public String noEvidenceMessage() {
        String configured = groundingNoEvidenceMessage == null ? "" : groundingNoEvidenceMessage.trim();
        if (!configured.isEmpty()) {
            return configured;
        }
        return "No encontre evidencia en tu base para responder eso. Puedes compartir una ruta, fecha, log o fragmento del documento?";
    }

    /**
     * Mensaje especifico cuando el retrieval no puede ejecutarse por una caida operacional.
     */
    public String retrievalUnavailableMessage() {
        String configured = groundingRetrievalUnavailableMessage == null ? "" : groundingRetrievalUnavailableMessage.trim();
        if (!configured.isEmpty()) {
            return configured;
        }
        return "No puedo consultar la base RAG en este momento. Intentalo de nuevo en unos segundos.";
    }

    /**
     * Construye la politica de sistema adicional que obliga a responder solo con contenido respaldado.
     */
    public String buildGroundingSystemPrompt(String fallbackMessage) {
        return """
                Politica de grounding obligatoria:
                - Responde SOLO con informacion respaldada por los fragmentos de este turno.
                - Si no hay suficiente contexto, responde exactamente: "%s"
                - No inventes ni especules.
                - Cuando uses fuentes RAG, cita [S#].
                """.formatted(fallbackMessage);
    }

    /**
     * Indica si el turno debe exigir grounding estricto en la salida final.
     */
    public boolean shouldEnforceGrounding(boolean ragUsed) {
        return groundingEnabled && ragUsed;
    }

    /**
     * Resume la fuerza del contexto recuperado usando cantidad y score de chunks de soporte.
     */
    public GroundingDecision assessGrounding(List<RagService.ScoredChunk> scored) {
        if (scored == null || scored.isEmpty()) {
            return new GroundingDecision(false, 0.0, 0, 0.0);
        }

        int minChunks = Math.max(1, groundingMinChunks);
        double minScore = clamp01(groundingMinScore);
        int supportingChunks = 0;
        double topScore = 0.0;
        double secondScore = 0.0;

        for (RagService.ScoredChunk chunk : scored) {
            double score = clamp01(chunk.score());
            if (score > topScore) {
                secondScore = topScore;
                topScore = score;
            } else if (score > secondScore) {
                secondScore = score;
            }
            if (score >= minScore) {
                supportingChunks++;
            }
        }

        // La decision mezcla calidad del mejor chunk, consistencia del top-2 y cobertura minima requerida.
        int topTwoCount = scored.size() >= 2 ? 2 : 1;
        double avgTopTwo = topTwoCount == 2 ? (topScore + secondScore) / 2.0 : topScore;
        double coverage = Math.min(1.0, supportingChunks / (double) minChunks);
        double confidence = clamp01((topScore * 0.55) + (avgTopTwo * 0.35) + (coverage * 0.10));
        boolean safe = supportingChunks >= minChunks && topScore >= minScore;

        return new GroundingDecision(safe, confidence, supportingChunks, topScore);
    }

    /**
     * Decide si el turno usa RAG fuerte, debil o no lo usa en absoluto.
     */
    public RagRoute resolveRagRoute(boolean ragNeeded, GroundingDecision groundingDecision) {
        if (!ragNeeded) {
            return RagRoute.NO_RAG;
        }
        if (isStrongRagGrounding(groundingDecision)) {
            return RagRoute.STRONG;
        }
        return RagRoute.WEAK;
    }

    /**
     * Detecta retrieval debil que probablemente requiera aclaracion en vez de respuesta factual.
     */
    public boolean isWeakRagGrounding(GroundingDecision groundingDecision) {
        if (groundingDecision == null) {
            return true;
        }
        return groundingDecision.supportingChunks() == 0
                || groundingDecision.topScore() < WEAK_RAG_TOP_SCORE_CUTOFF;
    }

    /**
     * Construye una repregunta corta cuando hay pistas parciales pero no suficiente evidencia util.
     */
    public String weakFallback(String userText) {
        String normalized = collapseSpaces(userText).toLowerCase(Locale.ROOT);
        boolean endpointLike = normalized.contains("endpoint")
                || normalized.contains("/api")
                || normalized.contains("http")
                || normalized.contains("status")
                || normalized.contains("ruta");
        boolean logLike = normalized.contains("log")
                || normalized.contains("error")
                || normalized.contains("stack")
                || normalized.contains("trace");

        if (endpointLike && !logLike) {
            return "Te refieres al endpoint exacto o al payload? Si pegas el endpoint completo o el log, lo clavo.";
        }
        if (logLike && !endpointLike) {
            return "Te refieres al error exacto del log o al endpoint que falla? Si pegas uno de los dos, lo clavo.";
        }
        return "Te refieres al endpoint exacto o al error del log? Si me pasas uno de los dos, lo clavo.";
    }

    /**
     * Refina o recorta la respuesta del modelo para eliminar relleno y reforzar grounding.
     */
    public String applyResponseGuard(String userText,
                                     String assistantText,
                                     List<RagService.ScoredChunk> scored,
                                     boolean ragUsed,
                                     boolean skipGuard) {
        if (!hasText(assistantText)) {
            return ragUsed ? fallbackMessage() : assistantText;
        }
        String original = assistantText.trim();
        if (!responseGuardEnabled || skipGuard) {
            return original;
        }

        // El guard solo se activa para respuestas RAG: verificar que las citas y el contenido
        // esten respaldados. Para respuestas directas (sin RAG) el modelo responde correctamente
        // con el system prompt y no se necesita un segundo paso de refinamiento.
        if (!ragUsed) {
            return original;
        }

        String guardModel = modelSelector.resolveResponseGuardModel();
        if (!hasText(guardModel)) {
            return original;
        }

        String question = truncateForGuard(collapseSpaces(userText), MAX_GUARD_QUESTION_CHARS);
        String sourceHints = buildSourceHints(scored);
        String currentAnswer = truncateForGuard(original, MAX_GUARD_ANSWER_CHARS);

        // Se construye un prompt de depuracion explicito para que el mini-modelo solo edite, no reescriba libremente.
        StringBuilder guardPrompt = new StringBuilder();
        guardPrompt.append("Pregunta original:\n").append(question).append("\n\n");
        if (hasText(sourceHints)) {
            guardPrompt.append("Fuentes citables disponibles:\n").append(sourceHints).append("\n\n");
        }
        guardPrompt.append("Respuesta actual (a depurar):\n").append(currentAnswer).append("\n\n");
        guardPrompt.append("Tarea:\n");
        guardPrompt.append("- Elimina cualquier frase no respaldada claramente por el contexto.\n");
        guardPrompt.append("- Elimina especulacion, ambiguedad o relleno.\n");
        guardPrompt.append("- Conserva SOLO informacion verificable.\n");
        guardPrompt.append("- Mantiene citas [S#] validas en cada afirmacion factual.\n");

        String guardSystemPrompt = responseGuardStrictMode
                ? """
                        Eres un verificador factual ultra-estricto para respuestas RAG.
                        Reglas obligatorias:
                        - No inventes ni agregues informacion nueva.
                        - Conserva solo contenido claramente respaldado por el contexto provisto.
                        - Elimina todo lo especulativo o ambiguo.
                        - Conserva citas [S#] y bloques de codigo cuando esten respaldados.
                        - Si no queda contenido verificable, responde exactamente: "%s"
                        - Devuelve solo la version final en Markdown limpio.
                        """
                : """
                        Eres un verificador factual estricto para respuestas RAG.
                        Reglas obligatorias:
                        - No inventes ni agregues informacion nueva.
                        - Conserva solo contenido respaldado por el contexto provisto.
                        - Elimina texto especulativo, ambiguo o irrelevante.
                        - Conserva citas [S#] validas cuando existan fuentes.
                        - Si no queda contenido verificable, responde exactamente: "%s"
                        - Devuelve solo la version final en Markdown limpio.
                        """;

        String systemPrompt = String.format(Locale.ROOT, guardSystemPrompt, fallbackMessage());

        List<OllamaClient.Message> guardMessages = List.of(
                new OllamaClient.Message("system", systemPrompt),
                new OllamaClient.Message("user", guardPrompt.toString())
        );

        try {
            String refined = ollama.chat(guardMessages, guardModel);
            if (!hasText(refined)) {
                return ragUsed ? fallbackMessage() : original;
            }

            String clean = refined.trim();
            // Si el guard expande demasiado la respuesta o rompe las citas, se descarta el resultado.
            if (clean.length() > original.length() + 260) {
                return ragUsed ? fallbackMessage() : original;
            }
            if (isFallbackMessage(clean)) {
                return fallbackMessage();
            }
            // En modo estricto se exigen citas [S#]; en modo normal se acepta la respuesta
            // aunque el modelo no haya incluido citas explicitamente.
            if (responseGuardStrictMode && !containsValidSourceCitation(clean, scored == null ? 0 : scored.size())) {
                return fallbackMessage();
            }
            return clean;
        } catch (Exception ex) {
            log.warn("No se pudo depurar respuesta con mini-modelo: {}", ex.getMessage());
            return ragUsed ? fallbackMessage() : original;
        }
    }

    /**
     * Valida si la respuesta final quedo suficientemente anclada segun las reglas configuradas.
     */
    public GroundingAnswerAssessment assessAnswerGrounding(String assistantText,
                                                           List<RagService.ScoredChunk> scored,
                                                           GroundingDecision groundingDecision) {
        if (!groundingEnabled) {
            return new GroundingAnswerAssessment(true, 0);
        }
        if (!groundingDecision.safe()) {
            return new GroundingAnswerAssessment(false, groundingDecision.supportingChunks());
        }
        if (!hasText(assistantText) || isFallbackMessage(assistantText)) {
            return new GroundingAnswerAssessment(false, 0);
        }
        if (scored == null || scored.isEmpty()) {
            return new GroundingAnswerAssessment(false, 0);
        }

        if (!groundingRequireCitations) {
            return new GroundingAnswerAssessment(true, groundingDecision.supportingChunks());
        }

        // Si las citas son obligatorias, una respuesta factual sin [S#] no se considera segura.
        Set<Integer> citations = extractValidSourceCitations(assistantText, scored.size());
        if (citations.isEmpty()) {
            return new GroundingAnswerAssessment(false, groundingDecision.supportingChunks());
        }

        return new GroundingAnswerAssessment(true, citations.size());
    }

    /**
     * Decide si conviene reintentar con el modelo principal tras una respuesta mal anclada.
     */
    public boolean shouldRetryWithPrimaryModel(String currentModel,
                                               boolean hasRagContext,
                                               GroundingAnswerAssessment assessment) {
        if (!groundingEnabled || !groundingRetryPrimaryModel) {
            return false;
        }
        if (!hasRagContext || assessment.safe()) {
            return false;
        }
        return !modelSelector.isPrimaryChatModel(currentModel);
    }

    /**
     * Verifica rapidamente si la respuesta contiene al menos una cita valida a las fuentes disponibles.
     */
    public boolean hasValidSourceCitations(String assistantText, List<RagService.ScoredChunk> scored) {
        int availableSources = scored == null ? 0 : scored.size();
        return containsValidSourceCitation(assistantText, availableSources);
    }

    /**
     * Construye pistas cortas de fuentes para el response guard.
     */
    private String buildSourceHints(List<RagService.ScoredChunk> scored) {
        if (scored == null || scored.isEmpty()) {
            return "";
        }

        int max = Math.min(MAX_GUARD_SOURCE_HINTS, scored.size());
        StringBuilder sb = new StringBuilder(max * 180);
        for (int i = 0; i < max; i++) {
            RagService.ScoredChunk entry = scored.get(i);
            String title = entry.chunk().getDocument().getTitle();
            String snippet = collapseSpaces(entry.chunk().getText());
            if (snippet.length() > 120) {
                snippet = snippet.substring(0, 120) + "...";
            }
            sb.append("- [S").append(i + 1).append("] ")
                    .append(title)
                    .append(": ")
                    .append(snippet)
                    .append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * Recorta texto antes de enviarlo al response guard.
     */
    private String truncateForGuard(String value, int maxChars) {
        if (!hasText(value)) {
            return "";
        }
        String clean = value.trim();
        if (clean.length() <= maxChars) {
            return clean;
        }
        return clean.substring(0, maxChars);
    }

    /**
     * Detecta si el retrieval es lo bastante fuerte para tratar el turno como RAG fuerte.
     */
    private boolean isStrongRagGrounding(GroundingDecision groundingDecision) {
        if (groundingDecision == null) {
            return false;
        }
        int requiredChunks = Math.max(STRONG_RAG_MIN_CHUNKS_FLOOR, Math.max(1, groundingMinChunks));
        double requiredTopScore = Math.max(STRONG_RAG_TOP_SCORE_FLOOR, clamp01(groundingMinScore));
        return groundingDecision.supportingChunks() >= requiredChunks
                && groundingDecision.topScore() >= requiredTopScore;
    }

    /**
     * Comprueba si el texto cita alguna fuente existente.
     */
    private boolean containsValidSourceCitation(String text, int availableSources) {
        return !extractValidSourceCitations(text, availableSources).isEmpty();
    }

    /**
     * Extrae citas [S#] validas presentes en la respuesta.
     */
    private Set<Integer> extractValidSourceCitations(String text, int availableSources) {
        if (!hasText(text) || availableSources <= 0) {
            return Set.of();
        }

        Set<Integer> citations = new HashSet<>();
        Matcher matcher = SOURCE_CITATION_PATTERN.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group(1);
            if (!hasText(raw)) {
                continue;
            }
            try {
                int sourceIndex = Integer.parseInt(raw);
                if (sourceIndex >= 1 && sourceIndex <= availableSources) {
                    citations.add(sourceIndex);
                }
            } catch (NumberFormatException ignored) {
                // ignora citas invalidas
            }
        }
        return citations;
    }

    /**
     * Identifica si el texto coincide exactamente con el fallback configurado.
     */
    private boolean isFallbackMessage(String text) {
        return matchesConfiguredFallback(text, fallbackMessage())
                || matchesConfiguredFallback(text, noEvidenceMessage())
                || matchesConfiguredFallback(text, retrievalUnavailableMessage());
    }

    private boolean matchesConfiguredFallback(String text, String configuredMessage) {
        String normalizedText = normalizeFallbackText(text);
        String normalizedMessage = normalizeFallbackText(configuredMessage);
        return !normalizedText.isEmpty()
                && !normalizedMessage.isEmpty()
                && normalizedText.startsWith(normalizedMessage);
    }

    private String normalizeFallbackText(String text) {
        if (!hasText(text)) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Normaliza numeros al rango [0,1] para metricas de confianza.
     */
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

    /**
     * Colapsa espacios para prompts y heuristicas textuales.
     */
    private String collapseSpaces(String text) {
        if (!hasText(text)) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * Ayuda local para validar texto util.
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Resume si el contexto recuperado es suficientemente fuerte para responder con grounding.
     */
    public record GroundingDecision(boolean safe, double confidence, int supportingChunks, double topScore) {
    }

    /**
     * Resume si la respuesta final quedo anclada y cuantas fuentes validas uso.
     */
    public record GroundingAnswerAssessment(boolean safe, int groundedSources) {
    }

    /**
     * Indica la severidad del uso de RAG dentro del turno.
     */
    public enum RagRoute {
        NO_RAG,
        STRONG,
        WEAK
    }
}


