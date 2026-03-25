package com.example.apiasistente.rag.util;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utilidad compartida para normalización de texto.
 * Consolida la lógica duplicada entre RagService y ChatRagGateService.
 */
public final class TextNormalizer {

    private TextNormalizer() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Normaliza texto para búsqueda: minúsculas, quita puntuación excesiva.
     */
    public static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.toLowerCase()
                .replaceAll("[\\u00A0\\s]+", " ") // normaliza espacios
                .replaceAll("[^a-z0-9áéíóúñü\\s]+", " ") // quita puntuación
                .trim();
    }

    /**
     * Tokeniza texto normalizado en palabras únicas, opcionalmente filtrando stopwords.
     */
    public static Set<String> tokenize(String normalizedText) {
        return tokenize(normalizedText, Set.of());
    }

    /**
     * Tokeniza y filtra stopwords.
     */
    public static Set<String> tokenize(String normalizedText, Set<String> stopwords) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(normalizedText.split("\\s+"))
                .filter(token -> !token.isEmpty() && !stopwords.contains(token))
                .collect(Collectors.toSet());
    }

    /**
     * Estima cantidad de tokens (heurística aproximada).
     */
    public static int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // Heurística simple: dividir por espacios
        // Para mayor precisión, usar tokenizador real (BPE/SentencePiece)
        return text.split("\\s+").length;
    }
}
