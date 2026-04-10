package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.service.ChatPromptSignals;
import com.example.apiasistente.chat.service.ChatTurnPlanner;
import com.example.apiasistente.rag.repository.KnowledgeChunkRepository;
import com.example.apiasistente.rag.repository.KnowledgeDocumentRepository;
import com.example.apiasistente.rag.service.RagService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compuerta barata antes del retrieval RAG.
 *
 * Objetivo:
 * - Evitar el coste del embedding cuando la consulta no parece beneficiarse del corpus.
 * - Aprovechar la nueva estructura `documents/chunks` usando metadata barata:
 *   `documents.title`, `documents.source` y `chunks.tags`.
 *
 * Regla operativa:
 * - `REQUIRED`: usa RAG salvo que el corpus esté vacío.
 * - `PREFERRED`: solo usa RAG si la query es lo bastante específica y encuentra pistas en metadata/tags.
 */
@Service
public class ChatRagGateService {

    private static final Set<String> STOPWORDS = Set.of(
            "de", "la", "el", "los", "las", "y", "o", "u", "en", "por", "para", "con", "sin", "del", "al",
            "que", "como", "donde", "cuando", "cual", "cuales", "quien", "quienes", "porque", "sobre",
            "the", "and", "or", "for", "with", "from", "this", "that", "those", "these", "into", "your", "you"
    );

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final ChatRagDecisionEngine decisionEngine;

    @Value("${chat.rag-gate.enabled:true}")
    private boolean gateEnabled;

    @Value("${chat.rag-gate.min-preferred-query-chars:18}")
    private int minPreferredQueryChars;

    @Value("${chat.rag-gate.min-preferred-query-tokens:2}")
    private int minPreferredQueryTokens;

    @Value("${chat.rag-gate.max-probe-terms:3}")
    private int maxProbeTerms;

    // Cache del tamano del corpus por lista de owners: evita 2 COUNT queries por turno.
    // TTL corto (30s) porque los documentos cambian poco durante una sesion de chat.
    private static final long CORPUS_CACHE_TTL_MS = 30_000L;
    private record CorpusCacheEntry(long docs, long chunks, long expiresAt) {}
    private final ConcurrentHashMap<String, CorpusCacheEntry> corpusCache = new ConcurrentHashMap<>();

    public ChatRagGateService(KnowledgeDocumentRepository documentRepository,
                              KnowledgeChunkRepository chunkRepository,
                              ChatRagDecisionEngine decisionEngine) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.decisionEngine = decisionEngine;
    }

    /**
     * Evaluates RAG eligibility using cached corpus stats; checks query validity and metadata matches
     */
    public GateDecision evaluate(ChatTurnPlanner.TurnPlan turnPlan,
                                 ChatPromptSignals.RagDecision requestedRagDecision,
                                 String userText,
                                 String owner,
                                 String scopedOwner,
                                 boolean hasDocumentMedia) {
        ChatPromptSignals.RagDecision ragDecision = requestedRagDecision != null
                ? requestedRagDecision
                : (turnPlan == null
                ? ChatPromptSignals.RagDecision.off("Sin plan de turno")
                : turnPlan.ragDecision());
        if (ragDecision == null || !ragDecision.enabled()) {
            return GateDecision.skip("rag-off", List.of(RagService.GLOBAL_OWNER), 0, 0, List.of(), false);
        }

        // Corpus unificado: no hay aislamiento por usuario en RAG.
        // Todos los documentos son accesibles a cualquier usuario del sistema.
        List<String> owners = List.of(RagService.GLOBAL_OWNER);

        ChatRagDecisionEngine.DecisionAssessment assessment = decisionEngine.assessQuery(
                userText,
                turnPlan,
                hasDocumentMedia
        );

        if (!gateEnabled) {
            return GateDecision.allow("gate-disabled", owners, 0, 0, List.of(), assessment);
        }

        // Cache de tamano del corpus compartido (sin filtro de propietario).
        long now = System.currentTimeMillis();
        CorpusCacheEntry cached = corpusCache.get("shared");
        long activeDocuments;
        long activeChunks;
        if (cached != null && cached.expiresAt() > now) {
            activeDocuments = cached.docs();
            activeChunks = cached.chunks();
        } else {
            activeDocuments = documentRepository.countByActiveTrue();
            activeChunks = chunkRepository.countActive();
            corpusCache.put("shared", new CorpusCacheEntry(activeDocuments, activeChunks, now + CORPUS_CACHE_TTL_MS));
        }

        // Si no hay corpus activo, no tiene sentido pagar la latencia del embedding.
        if (activeDocuments <= 0 || activeChunks <= 0) {
            boolean forceNoEvidence = ragDecision.requiresEvidence()
                    || assessment.queryType() == ChatRagDecisionEngine.QueryType.PERSONAL
                    || assessment.queryType() == ChatRagDecisionEngine.QueryType.REALTIME
                    || hasDocumentMedia;
            return new GateDecision(
                    false,
                    forceNoEvidence,
                    "corpus-vacio",
                    owners,
                    activeDocuments,
                    activeChunks,
                    List.of(),
                    assessment
            );
        }

        if (ragDecision.requiresEvidence()) {
            return GateDecision.allow("rag-required", owners, activeDocuments, activeChunks, List.of(), assessment);
        }

        if (hasDocumentMedia) {
            return GateDecision.allow("adjunto-documental", owners, activeDocuments, activeChunks, List.of("adjunto"), assessment);
        }

        // Solo saltamos con la decision del engine si el LLM lo confirmo o si la query es claramente general.
        // Para TECHNICAL y OTHER con assessment solo heuristico dejamos pasar al probe y al HNSW:
        // el assessment heuristico no puede saber si el corpus tiene contenido relevante para esas queries.
        if (!assessment.needsRag()
                && (assessment.usedLlm()
                || assessment.queryType() == ChatRagDecisionEngine.QueryType.GENERAL)) {
            return GateDecision.skip(
                    "decision-engine-no-rag",
                    owners,
                    activeDocuments,
                    activeChunks,
                    List.of(),
                    false,
                    assessment
            );
        }

        String normalizedQuery = normalize(userText);
        if (normalizedQuery.length() < Math.max(1, minPreferredQueryChars)) {
            if (shouldBypassMetadataProbe(assessment)) {
                return GateDecision.allow("decision-engine-query-corta", owners, activeDocuments, activeChunks, List.of(), assessment);
            }
            return GateDecision.skip("preferred-query-corta", owners, activeDocuments, activeChunks, List.of(), false, assessment);
        }

        List<String> probeTerms = selectProbeTerms(normalizedQuery);
        if (probeTerms.size() < Math.max(1, minPreferredQueryTokens)) {
            if (shouldBypassMetadataProbe(assessment)) {
                return GateDecision.allow("decision-engine-query-generica", owners, activeDocuments, activeChunks, probeTerms, assessment);
            }
            return GateDecision.skip("preferred-query-generica", owners, activeDocuments, activeChunks, probeTerms, false, assessment);
        }

        // Probe contra el corpus completo (sin filtro de propietario).
        List<String> matchedTerms = new ArrayList<>();
        for (String term : probeTerms) {
            long metadataHits = documentRepository.countActiveMetadataMatchesAll(term);
            long tagHits = metadataHits > 0 ? 0 : chunkRepository.countActiveTagMatchesAll(term);
            if (metadataHits > 0 || tagHits > 0) {
                matchedTerms.add(term);
                // Un solo termino coincidente es suficiente para habilitar RAG.
                break;
            }
        }

        if (!matchedTerms.isEmpty()) {
            return GateDecision.allow("preferred-metadata-hit", owners, activeDocuments, activeChunks, matchedTerms, assessment);
        }

        if (shouldBypassMetadataProbe(assessment)) {
            return GateDecision.allow("decision-engine-low-confidence", owners, activeDocuments, activeChunks, probeTerms, assessment);
        }

        return GateDecision.skip("preferred-sin-pistas-metadata", owners, activeDocuments, activeChunks, probeTerms, false, assessment);
    }

    private boolean shouldBypassMetadataProbe(ChatRagDecisionEngine.DecisionAssessment assessment) {
        if (assessment == null) {
            return false;
        }
        if (assessment.queryType() == ChatRagDecisionEngine.QueryType.PERSONAL
                || assessment.queryType() == ChatRagDecisionEngine.QueryType.REALTIME) {
            return true;
        }
        // Para TECHNICAL y OTHER no podemos descartar el corpus con la probe de metadata:
        // los documentos del scraper o fuentes externas pueden tener contenido relevante aunque
        // sus titulos/fuentes/tags no coincidan con los terminos de la query. Dejamos que HNSW decida.
        if (assessment.queryType() == ChatRagDecisionEngine.QueryType.TECHNICAL
                || assessment.queryType() == ChatRagDecisionEngine.QueryType.OTHER) {
            return true;
        }
        return assessment.needsExternalContext() || assessment.confidence() < 0.65;
    }

    private List<String> selectProbeTerms(String text) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String part : text.split("\\s+")) {
            String token = part == null ? "" : part.trim();
            if (token.length() < 4) {
                continue;
            }
            if (STOPWORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }

        return tokens.stream()
                .sorted((left, right) -> Integer.compare(right.length(), left.length()))
                .limit(Math.max(1, maxProbeTerms))
                .toList();
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

    public record GateDecision(boolean attemptRag,
                               boolean forceNoEvidence,
                               String reason,
                               List<String> owners,
                               long activeDocuments,
                               long activeChunks,
                               List<String> matchedTerms,
                               ChatRagDecisionEngine.DecisionAssessment decisionAssessment) {

        public GateDecision {
            reason = reason == null ? "" : reason.trim();
            owners = owners == null ? List.of() : List.copyOf(owners);
            activeDocuments = Math.max(0, activeDocuments);
            activeChunks = Math.max(0, activeChunks);
            matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
            decisionAssessment = decisionAssessment == null
                    ? new ChatRagDecisionEngine.DecisionAssessment(
                    ChatRagDecisionEngine.QueryType.OTHER,
                    false,
                    false,
                    0.0,
                    0.0,
                    false,
                    false,
                    false,
                    "heuristic",
                    "sin-decision"
            )
                    : decisionAssessment;
        }

        public GateDecision(boolean attemptRag,
                            boolean forceNoEvidence,
                            String reason,
                            List<String> owners,
                            long activeDocuments,
                            long activeChunks,
                            List<String> matchedTerms) {
            this(
                    attemptRag,
                    forceNoEvidence,
                    reason,
                    owners,
                    activeDocuments,
                    activeChunks,
                    matchedTerms,
                    null
            );
        }

        public static GateDecision allow(String reason,
                                         List<String> owners,
                                         long activeDocuments,
                                         long activeChunks,
                                         List<String> matchedTerms,
                                         ChatRagDecisionEngine.DecisionAssessment decisionAssessment) {
            return new GateDecision(true, false, reason, owners, activeDocuments, activeChunks, matchedTerms, decisionAssessment);
        }

        public static GateDecision allow(String reason,
                                         List<String> owners,
                                         long activeDocuments,
                                         long activeChunks,
                                         List<String> matchedTerms) {
            return allow(reason, owners, activeDocuments, activeChunks, matchedTerms, null);
        }

        public static GateDecision skip(String reason,
                                        List<String> owners,
                                        long activeDocuments,
                                        long activeChunks,
                                        List<String> matchedTerms,
                                        boolean forceNoEvidence,
                                        ChatRagDecisionEngine.DecisionAssessment decisionAssessment) {
            return new GateDecision(false, forceNoEvidence, reason, owners, activeDocuments, activeChunks, matchedTerms, decisionAssessment);
        }

        public static GateDecision skip(String reason,
                                        List<String> owners,
                                        long activeDocuments,
                                        long activeChunks,
                                        List<String> matchedTerms,
                                        boolean forceNoEvidence) {
            return skip(reason, owners, activeDocuments, activeChunks, matchedTerms, forceNoEvidence, null);
        }
    }
}
