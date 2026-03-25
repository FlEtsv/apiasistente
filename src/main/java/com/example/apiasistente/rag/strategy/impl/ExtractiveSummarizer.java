package com.example.apiasistente.rag.strategy.impl;

import com.example.apiasistente.rag.service.RagService;
import com.example.apiasistente.rag.strategy.CompressionStrategy;
import com.example.apiasistente.rag.util.TextNormalizer;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Estrategia de compresión extractiva: selecciona fragmentos relevantes de cada chunk.
 * Implementa la lógica actual de RagService.compressForPrompt() y compressChunkText().
 */
@Component
public class ExtractiveSummarizer implements CompressionStrategy {

    private static final Pattern FRAGMENT_SPLIT_PATTERN = Pattern.compile("(?m)\\n\\s*\\n+|(?<=[.!?])\\s+");

    @Override
    public List<RagService.CompressedChunk> compress(
            List<RagService.ScoredCandidate> chunks,
            String query,
            Set<String> queryTokens,
            int maxChunks,
            int maxSnippetsPerChunk,
            int maxCharsPerChunk
    ) {
        List<RagService.CompressedChunk> result = new ArrayList<>();

        for (int i = 0; i < Math.min(chunks.size(), maxChunks); i++) {
            RagService.ScoredCandidate sc = chunks.get(i);
            String compressed = compressChunkText(
                    sc.chunk().getText(),
                    queryTokens,
                    maxSnippetsPerChunk,
                    maxCharsPerChunk
            );
            result.add(new RagService.CompressedChunk(
                    sc.chunk(),
                    compressed,
                    sc.finalScore()
            ));
        }

        return result;
    }

    private String compressChunkText(
            String fullText,
            Set<String> queryTokens,
            int maxSnippets,
            int maxChars
    ) {
        if (fullText == null || fullText.isBlank()) {
            return "";
        }

        // Si el texto completo cabe, devolverlo directo
        if (fullText.length() <= maxChars) {
            return fullText.strip();
        }

        // Fragmentar por párrafos o sentencias
        String[] fragments = FRAGMENT_SPLIT_PATTERN.split(fullText);

        // Calcular score por fragmento
        List<FragmentScore> scored = new ArrayList<>();
        for (String frag : fragments) {
            if (frag.isBlank()) continue;
            String normalized = TextNormalizer.normalize(frag);
            Set<String> fragTokens = TextNormalizer.tokenize(normalized);

            Set<String> intersection = new HashSet<>(queryTokens);
            intersection.retainAll(fragTokens);

            double coverage = fragTokens.isEmpty() ? 0.0 : (double) intersection.size() / fragTokens.size();
            double jaccard = (queryTokens.size() + fragTokens.size() - intersection.size()) == 0 ? 0.0
                    : (double) intersection.size() / (queryTokens.size() + fragTokens.size() - intersection.size());

            double score = 0.7 * coverage + 0.3 * jaccard;
            scored.add(new FragmentScore(frag.strip(), score));
        }

        // Ordenar por score descendente
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        // Seleccionar mejores fragmentos hasta llenar el límite de chars
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (FragmentScore fs : scored) {
            if (count >= maxSnippets) break;
            if (result.length() + fs.text.length() + 5 > maxChars) {
                // Intentar truncar el último fragmento
                int remaining = maxChars - result.length() - 5;
                if (remaining > 50) { // solo si queda espacio razonable
                    result.append(fs.text, 0, remaining).append("...");
                }
                break;
            }
            if (!result.isEmpty()) {
                result.append("\n\n");
            }
            result.append(fs.text);
            count++;
        }

        return result.toString();
    }

    private record FragmentScore(String text, double score) {
    }
}
