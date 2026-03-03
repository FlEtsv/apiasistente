package com.example.apiasistente.chat.service;

import org.springframework.stereotype.Component;

/**
 * Planificador heuristico para turnos de chat.
 */
@Component
public class ChatTurnPlanner {

    /**
     * Resume como deberia ejecutarse el turno antes de entrar a retrieval o generacion.
     */
    public TurnPlan plan(String rawText,
                         boolean hasMedia,
                         boolean hasImageMedia,
                         boolean hasDocumentMedia) {
        String text = normalize(rawText);
        ChatPromptSignals.RagDecision ragDecision = ChatPromptSignals.ragDecision(text, hasDocumentMedia);
        ChatPromptSignals.IntentRoute intentRoute = ChatPromptSignals.routeIntent(text, hasDocumentMedia);
        boolean complexQuery = ChatPromptSignals.isComplexQuery(text);
        boolean multiStepQuery = ChatPromptSignals.isMultiStepQuery(text);
        int words = countWords(text);
        boolean ragNeeded = ragDecision.enabled();

        // Este bloque decide la "profundidad" esperada del turno para routing y telemetria.
        ReasoningLevel reasoningLevel = resolveReasoningLevel(
                intentRoute,
                ragNeeded,
                complexQuery,
                multiStepQuery,
                hasMedia,
                hasImageMedia,
                hasDocumentMedia,
                words
        );

        // Esta confianza no es factual; solo mide cuan clara parece la clasificacion heuristica.
        double confidence = estimateConfidence(
                intentRoute,
                ragDecision,
                reasoningLevel,
                complexQuery,
                multiStepQuery,
                words
        );

        return new TurnPlan(
                intentRoute,
                ragNeeded,
                reasoningLevel,
                complexQuery,
                multiStepQuery,
                confidence,
                ragDecision
        );
    }

    /**
     * Estima cuanta capacidad conviene asignar al turno segun senales del texto y adjuntos.
     */
    private ReasoningLevel resolveReasoningLevel(ChatPromptSignals.IntentRoute intentRoute,
                                                 boolean ragNeeded,
                                                 boolean complexQuery,
                                                 boolean multiStepQuery,
                                                 boolean hasMedia,
                                                 boolean hasImageMedia,
                                                 boolean hasDocumentMedia,
                                                 int words) {
        if (complexQuery || multiStepQuery || words >= 36 || hasDocumentMedia) {
            return ReasoningLevel.HIGH;
        }

        if (intentRoute == ChatPromptSignals.IntentRoute.TEXT_RENDER && !hasMedia) {
            return ReasoningLevel.LOW;
        }

        if (intentRoute == ChatPromptSignals.IntentRoute.SMALL_TALK && !hasMedia) {
            return ReasoningLevel.LOW;
        }

        if (ragNeeded || hasImageMedia || words >= 12) {
            return ReasoningLevel.MEDIUM;
        }

        return ReasoningLevel.LOW;
    }

    /**
     * Calcula una confianza heuristica de la clasificacion del turno.
     */
    private double estimateConfidence(ChatPromptSignals.IntentRoute intentRoute,
                                      ChatPromptSignals.RagDecision ragDecision,
                                      ReasoningLevel reasoningLevel,
                                      boolean complexQuery,
                                      boolean multiStepQuery,
                                      int words) {
        double score = 0.45;

        if (intentRoute == ChatPromptSignals.IntentRoute.SMALL_TALK && words <= 8) {
            score += 0.25;
        }
        if (ragDecision.mode() == ChatPromptSignals.RagMode.REQUIRED) {
            score += 0.22;
        } else if (ragDecision.mode() == ChatPromptSignals.RagMode.PREFERRED) {
            score += 0.12;
        }
        if (intentRoute == ChatPromptSignals.IntentRoute.TEXT_RENDER) {
            score += 0.15;
        }
        if (reasoningLevel == ReasoningLevel.HIGH && (complexQuery || multiStepQuery)) {
            score += 0.10;
        }
        if (words <= 2) {
            score -= 0.10;
        }

        return clamp01(score);
    }

    /**
     * Normaliza el texto del usuario antes de aplicar heuristicas.
     */
    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    /**
     * Cuenta palabras para reglas simples de routing.
     */
    private static int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.split("\\s+").length;
    }

    /**
     * Limita un valor numerico al rango [0,1].
     */
    private static double clamp01(double value) {
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
     * Representa la profundidad de razonamiento esperada para el turno.
     */
    public enum ReasoningLevel {
        LOW,
        MEDIUM,
        HIGH
    }

    /**
     * Agrupa la planificacion completa del turno para reutilizarla entre capas.
     */
    public record TurnPlan(ChatPromptSignals.IntentRoute intentRoute,
                           boolean ragNeeded,
                           ReasoningLevel reasoningLevel,
                           boolean complexQuery,
                           boolean multiStepQuery,
                           double confidence,
                           ChatPromptSignals.RagDecision ragDecision) {

        public TurnPlan {
            // Asegura que el plan siempre salga completo aunque alguna heuristica entregue nulos.
            ragDecision = ragDecision == null
                    ? defaultRagDecision(ragNeeded)
                    : ragDecision;
            ragNeeded = ragDecision.enabled();
            intentRoute = intentRoute == null ? ChatPromptSignals.IntentRoute.TASK_SIMPLE : intentRoute;
            reasoningLevel = reasoningLevel == null ? ReasoningLevel.MEDIUM : reasoningLevel;
            confidence = clamp01(confidence);
        }

        public TurnPlan(ChatPromptSignals.IntentRoute intentRoute,
                        boolean ragNeeded,
                        ReasoningLevel reasoningLevel,
                        boolean complexQuery,
                        boolean multiStepQuery,
                        double confidence) {
            this(
                    intentRoute,
                    ragNeeded,
                    reasoningLevel,
                    complexQuery,
                    multiStepQuery,
                    confidence,
                    defaultRagDecision(ragNeeded)
            );
        }

        /**
         * Genera una decision por defecto para compatibilidad con llamadas antiguas.
         */
        private static ChatPromptSignals.RagDecision defaultRagDecision(boolean ragNeeded) {
            return ragNeeded
                    ? ChatPromptSignals.RagDecision.preferred("RAG activado por compatibilidad", java.util.List.of())
                    : ChatPromptSignals.RagDecision.off("Sin RAG");
        }
    }
}
