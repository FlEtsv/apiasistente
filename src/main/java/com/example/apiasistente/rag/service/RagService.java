package com.example.apiasistente.rag.service;

import com.example.apiasistente.rag.dto.RagContextStatsDto;
import com.example.apiasistente.rag.dto.SourceDto;
import com.example.apiasistente.rag.entity.KnowledgeChunk;
import com.example.apiasistente.rag.entity.KnowledgeDocument;
import com.example.apiasistente.rag.entity.KnowledgeVector;
import com.example.apiasistente.rag.repository.KnowledgeChunkRepository;
import com.example.apiasistente.rag.repository.KnowledgeDocumentRepository;
import com.example.apiasistente.rag.repository.KnowledgeVectorRepository;
import com.example.apiasistente.rag.util.TextChunker;
import com.example.apiasistente.rag.util.VectorMath;
import com.example.apiasistente.shared.ai.OllamaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * Servicio central del RAG.
 *
 * Flujo canonico desde esta version:
 * 1. `documents` guarda identidad y metadata estable del documento.
 * 2. `chunks` guarda texto append-only y metadata operativa por fragmento.
 * 3. `vectors` guarda embeddings durables para reconstruccion.
 * 4. `RagVectorIndexService` mantiene el indice HNSW que realmente usa retrieval.
 *
 * Responsabilidad:
 * - Coordinar ingesta, versionado, retrieval y borrado sobre la arquitectura nueva.
 * - Mantener aislados los detalles de chunking, fingerprinting e indexacion del resto de la app.
 */
@Service
public class RagService {

    public static final String GLOBAL_OWNER = "global";
    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    // Cache de embeddings de queries: evita llamar a Ollama para la misma consulta repetida.
    // TTL de 30 minutos es adecuado: las queries tipicas de chat se repiten dentro de una sesion.
    private static final int EMBEDDING_CACHE_MAX = 500;
    private static final long EMBEDDING_CACHE_TTL_MS = 1_800_000L; // 30 min
    private record CachedEmbedding(double[] vec, long expiresAt) {}
    private final ConcurrentHashMap<String, CachedEmbedding> embeddingCache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> embeddingCacheOrder = new ConcurrentLinkedDeque<>();

    private static final Set<String> STOPWORDS = Set.of(
            "de", "la", "el", "los", "las", "y", "o", "u", "en", "por", "para", "con", "sin", "del", "al",
            "que", "como", "donde", "cuando", "cual", "cuales", "quien", "quienes", "porque", "sobre",
            "the", "and", "or", "for", "with", "from", "this", "that", "those", "these", "into", "your", "you"
    );

    private final KnowledgeDocumentRepository docRepo;
    private final KnowledgeChunkRepository chunkRepo;
    private final KnowledgeVectorRepository vectorRepo;
    private final RagVectorIndexService vectorIndexService;
    private final RagOpsService ragOpsService;
    private final OllamaClient ollama;

    @Value("${rag.top-k:10}")
    private int topK;

    @Value("${rag.context.max-chunks:5}")
    private int contextMaxChunks;

    @Value("${rag.context.max-snippets-per-chunk:2}")
    private int maxSnippetsPerChunk;

    @Value("${rag.context.max-chars-per-chunk:420}")
    private int maxCharsPerChunk;

    @Value("${rag.chunk.size:900}")
    private int chunkSize;

    @Value("${rag.chunk.overlap:150}")
    private int overlap;

    @Value("${rag.rerank.candidates:12}")
    private int rerankCandidates;

    @Value("${rag.rerank.lambda:0.65}")
    private double rerankLambda;

    @Value("${rag.score.evidence-threshold:0.45}")
    private double evidenceThreshold;

    @Value("${rag.hybrid.semantic-weight:0.80}")
    private double semanticWeight;

    @Value("${rag.hybrid.lexical-weight:0.20}")
    private double lexicalWeight;

    @Value("${rag.hybrid.exact-match-boost:0.12}")
    private double exactMatchBoost;

    @Value("${rag.owner.global-boost:0.03}")
    private double globalOwnerBoost;

    @Value("${rag.owner.user-boost:0.05}")
    private double userOwnerBoost;

    public RagService(KnowledgeDocumentRepository docRepo,
                      KnowledgeChunkRepository chunkRepo,
                      KnowledgeVectorRepository vectorRepo,
                      RagVectorIndexService vectorIndexService,
                      RagOpsService ragOpsService,
                      OllamaClient ollama) {
        this.docRepo = docRepo;
        this.chunkRepo = chunkRepo;
        this.vectorRepo = vectorRepo;
        this.vectorIndexService = vectorIndexService;
        this.ragOpsService = ragOpsService;
        this.ollama = ollama;
    }

    // ----------------- INGESTA -----------------

    /**
     * Atajo para documentos compartidos.
     */
    @Transactional
    public KnowledgeDocument upsertDocument(String title, String content) {
        return upsertDocumentForOwner(GLOBAL_OWNER, title, content);
    }

    /**
     * Punto de entrada principal de ingesta.
     *
     * Comportamiento actual:
     * - Si el documento no existe, se crea.
     * - Si existe con el mismo fingerprint, se reutiliza la version activa.
     * - Si existe pero cambia el contenido, se crea una nueva version activa y la anterior se archiva.
     */
    @Transactional
    public KnowledgeDocument upsertDocumentForOwner(String owner, String title, String content) {
        return upsertDocumentForOwner(owner, title, content, "api", null);
    }

    /**
     * Variante con metadata explicita para futuras integraciones manuales.
     */
    @Transactional
    public KnowledgeDocument upsertDocumentForOwner(String owner,
                                                    String title,
                                                    String content,
                                                    String source,
                                                    String tags) {
        return upsertStructuredDocumentForOwner(owner, title, content, source, tags, null, List.of());
    }

    /**
     * Entrada estructurada para scraper e integraciones que ya entregan chunks explicitos.
     * Si `chunks` viene vacio, cae al chunking interno legacy.
     */
    @Transactional
    public KnowledgeDocument upsertStructuredDocumentForOwner(String owner,
                                                              String title,
                                                              String content,
                                                              String source,
                                                              String tags,
                                                              String referenceUrl,
                                                              List<IncomingChunk> chunks) {
        String normalizedOwner = normalizeOwner(owner);
        String normalizedTitle = normalizeTitle(title);
        String normalizedReferenceUrl = normalizeReferenceUrl(referenceUrl);
        String normalizedSource = normalizeSource(source, normalizedReferenceUrl == null ? "api" : "scraper");
        String normalizedTags = normalizeTags(tags);
        List<PreparedChunkInput> preparedChunks = prepareChunks(chunks, content, normalizedSource, normalizedTags);
        String documentFingerprint = computeDocumentFingerprint(preparedChunks);

        KnowledgeDocument activeDoc = docRepo.findFirstByOwnerAndTitleIgnoreCaseAndActiveTrue(normalizedOwner, normalizedTitle)
                .orElse(null);

        if (activeDoc != null && documentFingerprint.equals(trimToEmpty(activeDoc.getContentFingerprint()))) {
            // Mismo documento logico: solo refrescamos metadata estable si el origen cambio.
            activeDoc.setSource(normalizedSource);
            activeDoc.setReferenceUrl(normalizedReferenceUrl);
            activeDoc.setContentFingerprint(documentFingerprint);
            KnowledgeDocument saved = docRepo.save(activeDoc);
            ragOpsService.recordIngest(
                    normalizedOwner,
                    normalizedTitle,
                    saved.getId(),
                    preparedChunks.size(),
                    normalizedSource,
                    false,
                    normalizedReferenceUrl
            );
            return saved;
        }

        KnowledgeDocument newDoc = new KnowledgeDocument();
        newDoc.setOwner(normalizedOwner);
        newDoc.setTitle(normalizedTitle);
        newDoc.setSource(normalizedSource);
        newDoc.setReferenceUrl(normalizedReferenceUrl);
        newDoc.setActive(true);
        newDoc.setContentFingerprint(documentFingerprint);
        newDoc = docRepo.save(newDoc);

        if (activeDoc != null) {
            archiveDocumentVersion(activeDoc, newDoc.getId());
        }

        List<KnowledgeChunk> persistedChunks = persistChunks(newDoc, preparedChunks);
        persistVectorsAndIndex(newDoc.getOwner(), persistedChunks);

        log.debug(
                "RAG ingest owner='{}' title='{}' docId={} chunks={} source='{}' referenceUrl='{}'",
                normalizedOwner,
                normalizedTitle,
                newDoc.getId(),
                persistedChunks.size(),
                normalizedSource,
                normalizedReferenceUrl
        );
        ragOpsService.recordIngest(
                normalizedOwner,
                normalizedTitle,
                newDoc.getId(),
                persistedChunks.size(),
                normalizedSource,
                true,
                normalizedReferenceUrl
        );
        return newDoc;
    }

    @Transactional
    public KnowledgeDocument storeMemory(String username, String title, String content) {
        String user = normalizeOwner(username);
        String cleanTitle = trimToEmpty(title);
        if (cleanTitle.isBlank()) {
            cleanTitle = "Memoria/" + user + "/" + Instant.now();
        }
        return upsertDocumentForOwner(user, cleanTitle, content, "memory", "memory");
    }

    /**
     * Borrado duro para casos criticos o decisiones administrativas.
     */
    @Transactional
    public boolean deleteDocumentById(Long documentId) {
        if (documentId == null) {
            return false;
        }

        KnowledgeDocument doc = docRepo.findById(documentId).orElse(null);
        if (doc == null) {
            return false;
        }

        List<Long> chunkIds = chunkRepo.findIdsByDocumentId(documentId);
        if (!chunkIds.isEmpty()) {
            vectorRepo.deleteByChunkIdIn(chunkIds);
            vectorIndexService.deleteChunkIds(chunkIds);
            chunkRepo.deleteByDocument_Id(documentId);
        }
        docRepo.delete(doc);

        log.debug("RAG delete owner='{}' title='{}' docId={} chunks={}",
                normalizeOwner(doc.getOwner()),
                doc.getTitle(),
                documentId,
                chunkIds.size());
        ragOpsService.recordDocumentDelete(
                normalizeOwner(doc.getOwner()),
                doc.getTitle(),
                documentId,
                chunkIds.size(),
                "rag-service"
        );
        return true;
    }

    /**
     * Borrado selectivo de chunks. Se usa desde mantenimiento para podar ruido sin rehacer todo el documento.
     */
    @Transactional
    public int deleteChunkIds(String owner, List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return 0;
        }

        String normalizedOwner = normalizeOwner(owner);
        List<KnowledgeChunk> chunks = chunkRepo.findWithDocumentByIdIn(chunkIds).stream()
                .filter(chunk -> chunk.getId() != null)
                .filter(chunk -> chunk.getDocument() != null)
                .filter(chunk -> normalizedOwner.equalsIgnoreCase(normalizeOwner(chunk.getDocument().getOwner())))
                .toList();

        if (chunks.isEmpty()) {
            return 0;
        }

        List<Long> ids = chunks.stream().map(KnowledgeChunk::getId).toList();
        vectorRepo.deleteByChunkIdIn(ids);
        vectorIndexService.deleteChunkIds(ids);
        chunkRepo.deleteAllByIdInBatch(ids);

        log.debug("RAG prune owner='{}' chunks={}", normalizedOwner, ids.size());
        ragOpsService.recordChunkPrune(normalizedOwner, ids.size());
        return ids.size();
    }

    // ----------------- RETRIEVAL -----------------

    /**
     * Retrieval sin filtro de propietario: busca en todo el corpus compartido.
     * Punto de entrada principal del chat RAG. No hay aislamiento por usuario en RAG.
     */
    public RetrievalResult retrieveShared(String query) {
        return retrieveForOwners(query, null);
    }

    /** Alias de compatibilidad — redirige al corpus compartido. */
    public List<ScoredChunk> retrieveTopK(String query) {
        return retrieveShared(query).contextChunks();
    }

    public List<ScoredChunk> retrieveTopKForOwnerOrGlobal(String query, String owner) {
        return retrieveShared(query).contextChunks();
    }

    public RetrievalResult retrieveForOwnerOrGlobal(String query, String owner) {
        return retrieveShared(query);
    }

    public List<ScoredChunk> retrieveTopKForOwnerScopedAndGlobal(String query,
                                                                 String owner,
                                                                 String scopedOwner) {
        return retrieveShared(query).contextChunks();
    }

    public RetrievalResult retrieveForOwnerScopedAndGlobal(String query,
                                                           String owner,
                                                           String scopedOwner) {
        return retrieveShared(query);
    }

    public RagContextStatsDto contextStatsForOwnerOrGlobal(String owner) {
        // Corpus unificado: se devuelven siempre las metricas globales del corpus completo.
        long totalDocuments = docRepo.countByActiveTrue();
        long totalChunks = chunkRepo.countActive();

        long globalDocuments = totalDocuments;
        long globalChunks = totalChunks;
        long ownerDocuments = 0;
        long ownerChunks = 0;

        Instant lastUpdatedAt = docRepo.findLastActiveUpdateAt();

        return new RagContextStatsDto(
                GLOBAL_OWNER,
                totalDocuments,
                totalChunks,
                globalDocuments,
                globalChunks,
                ownerDocuments,
                ownerChunks,
                lastUpdatedAt,
                topK,
                chunkSize,
                overlap
        );
    }

    public List<ScoredChunk> retrieveTopKForOwners(String query, List<String> owners) {
        return retrieveShared(query).contextChunks();
    }

    /**
     * Retrieval hibrido sin filtro de propietario cuando owners == null.
     * owners == null => busca en TODO el corpus (sin filtrar por owner en HNSW).
     * owners != null (legacy) => filtra por los owners indicados.
     */
    public RetrievalResult retrieveForOwners(String query, List<String> owners) {
        long embeddingStartNanos = System.nanoTime();
        double[] queryEmbedding = getCachedEmbedding(query);
        double queryEmbeddingTimeMs = nanosToMillis(embeddingStartNanos);
        boolean noOwnerFilter = owners == null;
        List<String> ownersClean = noOwnerFilter ? List.of(GLOBAL_OWNER) : normalizeOwners(owners);
        if (queryEmbedding.length == 0) {
            return finalizeRetrieval(
                    query,
                    ownersClean,
                    0,
                    0,
                    0L,
                    RetrievalResult.empty(ownersClean, queryEmbeddingTimeMs, topK, evidenceThreshold)
            );
        }

        int retrievalTopK = Math.max(1, topK);
        // Sin filtro de propietario usamos factor 2 fijo; con filtro escalamos por numero de owners.
        int semanticCandidateLimit = Math.max(retrievalTopK, rerankCandidates)
                * (noOwnerFilter ? 2 : Math.max(2, ownersClean.size()));
        long retrievalStartNanos = System.nanoTime();

        // Fase 1: recuperamos candidatos semanticos baratos desde HNSW.
        // null como owners => sin filtro de propietario => todos los documentos activos.
        List<RagVectorIndexService.SearchHit> searchHits = vectorIndexService.search(
                noOwnerFilter ? null : ownersClean,
                queryEmbedding,
                semanticCandidateLimit
        );
        if (searchHits.isEmpty()) {
            return finalizeRetrieval(
                    query,
                    ownersClean,
                    0,
                    0,
                    retrievalStartNanos,
                    emptyResult(ownersClean, queryEmbeddingTimeMs, retrievalTopK)
            );
        }

        List<Long> candidateIds = searchHits.stream()
                .map(RagVectorIndexService.SearchHit::chunkId)
                .distinct()
                .toList();

        Map<Long, KnowledgeChunk> chunkById = chunkRepo.findActiveWithDocumentByIdIn(candidateIds).stream()
                .collect(Collectors.toMap(KnowledgeChunk::getId, chunk -> chunk));
        Map<Long, double[]> embeddingByChunkId = vectorRepo.findPayloadByChunkIds(candidateIds).stream()
                .collect(Collectors.toMap(
                        KnowledgeVectorRepository.VectorPayloadView::getChunkId,
                        view -> VectorMath.normalize(ollama.fromJson(view.getEmbeddingJson()))
                ));

        // Fase 2: reconstruimos el contexto minimo necesario para rescoring y MMR.
        List<CandidateChunk> semanticCandidates = new ArrayList<>(searchHits.size());
        for (RagVectorIndexService.SearchHit hit : searchHits) {
            KnowledgeChunk chunk = chunkById.get(hit.chunkId());
            if (chunk == null) {
                continue;
            }
            double[] embedding = embeddingByChunkId.getOrDefault(hit.chunkId(), new double[0]);
            semanticCandidates.add(new CandidateChunk(
                    hit.chunkId(),
                    hit.owner(),
                    normalizeSemanticScore(hit.score()),
                    normalizeSemanticScore(hit.score()),
                    embedding
            ));
        }
        if (semanticCandidates.isEmpty()) {
            return finalizeRetrieval(
                    query,
                    ownersClean,
                    searchHits.size(),
                    0,
                    retrievalStartNanos,
                    emptyResult(ownersClean, queryEmbeddingTimeMs, retrievalTopK)
            );
        }

        List<CandidateChunk> hybridCandidates = applyHybridScoring(query, semanticCandidates, chunkById);
        if (hybridCandidates.isEmpty()) {
            return finalizeRetrieval(
                    query,
                    ownersClean,
                    semanticCandidates.size(),
                    0,
                    retrievalStartNanos,
                    emptyResult(ownersClean, queryEmbeddingTimeMs, retrievalTopK)
            );
        }

        hybridCandidates.sort(Comparator.comparingDouble(CandidateChunk::score).reversed());
        List<CandidateChunk> reranked = rerankWithMmr(hybridCandidates, retrievalTopK, rerankLambda);
        if (reranked.isEmpty()) {
            return finalizeRetrieval(
                    query,
                    ownersClean,
                    semanticCandidates.size(),
                    0,
                    retrievalStartNanos,
                    emptyResult(ownersClean, queryEmbeddingTimeMs, retrievalTopK)
            );
        }

        List<ScoredChunk> retrieved = reranked.stream()
                .map(sc -> new ScoredChunk(chunkById.get(sc.chunkId()), sc.score()))
                .filter(sc -> sc.chunk() != null)
                .toList();

        List<ScoredChunk> evidence = applyEvidenceThreshold(retrieved);
        List<ScoredChunk> context = compressForPrompt(query, evidence);
        double maxSimilarity = retrieved.stream().mapToDouble(ScoredChunk::score).max().orElse(0.0);
        double avgSimilarity = retrieved.stream().mapToDouble(ScoredChunk::score).average().orElse(0.0);
        List<Long> chunkIds = context.stream()
                .map(ScoredChunk::chunk)
                .filter(chunk -> chunk != null && chunk.getId() != null)
                .map(KnowledgeChunk::getId)
                .toList();
        List<String> sourceDocs = context.stream()
                .map(ScoredChunk::chunk)
                .filter(chunk -> chunk != null && chunk.getDocument() != null)
                .map(chunk -> chunk.getDocument().getTitle())
                .distinct()
                .toList();
        int contextTokens = estimateTokens(context);

        if (log.isDebugEnabled()) {
            double elapsedMs = nanosToMillis(retrievalStartNanos);
            log.debug(
                    "RAG retrieve owners={} candidates={} selected={} evidence={} context={} elapsedMs={}",
                    ownersClean,
                    semanticCandidates.size(),
                    retrieved.size(),
                    evidence.size(),
                    context.size(),
                    String.format(Locale.US, "%.2f", elapsedMs)
            );
        }

        return finalizeRetrieval(
                query,
                ownersClean,
                semanticCandidates.size(),
                evidence.size(),
                retrievalStartNanos,
                new RetrievalResult(
                retrieved,
                context,
                new RetrievalStats(
                        ownersClean,
                        queryEmbeddingTimeMs,
                        retrievalTopK,
                        retrieved.size(),
                        maxSimilarity,
                        avgSimilarity,
                        clamp01(evidenceThreshold),
                        contextTokens,
                        chunkIds,
                        sourceDocs
                )
                )
        );
    }

    public List<SourceDto> toSourceDtos(List<ScoredChunk> scored) {
        return scored.stream().map(sc -> {
            KnowledgeChunk c = sc.chunk();
            KnowledgeDocument d = c.getDocument();
            String text = sc.effectiveText();
            String snippet = text.length() > 220 ? text.substring(0, 220) + "..." : text;
            return new SourceDto(c.getId(), d.getId(), d.getTitle(), sc.score(), snippet);
        }).toList();
    }

    // ----------------- COMPRESION Y RANKING -----------------

    private RetrievalResult emptyResult(List<String> ownersClean, double queryEmbeddingTimeMs, int retrievalTopK) {
        return new RetrievalResult(
                List.of(),
                List.of(),
                new RetrievalStats(
                        ownersClean,
                        queryEmbeddingTimeMs,
                        retrievalTopK,
                        0,
                        0.0,
                        0.0,
                        clamp01(evidenceThreshold),
                        0,
                        List.of(),
                        List.of()
                )
        );
    }

    /**
     * Cierra la telemetria del retrieval en un solo punto para que el metodo principal no repita logging.
     */
    private RetrievalResult finalizeRetrieval(String query,
                                              List<String> owners,
                                              int semanticCandidates,
                                              int evidenceChunks,
                                              long retrievalStartNanos,
                                              RetrievalResult result) {
        double retrievalMs = retrievalStartNanos <= 0L ? 0.0 : nanosToMillis(retrievalStartNanos);
        ragOpsService.recordRetrieval(
                query,
                owners,
                semanticCandidates,
                result.retrievedChunks().size(),
                evidenceChunks,
                result.contextChunks().size(),
                result.stats().queryEmbeddingTimeMs(),
                retrievalMs,
                result.stats().sourceDocs(),
                result.hasEvidence()
        );
        return result;
    }

    private List<ScoredChunk> applyEvidenceThreshold(List<ScoredChunk> scored) {
        if (scored == null || scored.isEmpty()) {
            return List.of();
        }
        double threshold = clamp01(evidenceThreshold);
        return scored.stream()
                .filter(chunk -> chunk != null && chunk.chunk() != null)
                .filter(chunk -> chunk.score() >= threshold)
                .toList();
    }

    private List<ScoredChunk> compressForPrompt(String query, List<ScoredChunk> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }

        int maxChunks = Math.max(1, contextMaxChunks);
        List<ScoredChunk> compressed = new ArrayList<>(Math.min(maxChunks, evidence.size()));
        for (ScoredChunk chunk : evidence) {
            if (compressed.size() >= maxChunks) {
                break;
            }
            String promptText = compressChunkText(query, chunk.chunk().getText());
            compressed.add(chunk.withPromptText(promptText));
        }
        return List.copyOf(compressed);
    }

    private String compressChunkText(String query, String chunkText) {
        if (!hasText(chunkText)) {
            return "";
        }

        String normalizedQuery = normalizeSearchText(query);
        Set<String> queryTokens = tokenize(normalizedQuery);
        String[] rawFragments = chunkText.split("(?m)\\n\\s*\\n+|(?<=[.!?])\\s+");
        List<FragmentCandidate> candidates = new ArrayList<>(rawFragments.length);

        for (int i = 0; i < rawFragments.length; i++) {
            String fragment = collapseSpaces(rawFragments[i]);
            if (!hasText(fragment)) {
                continue;
            }
            double fragmentScore = lexicalScore(normalizedQuery, queryTokens, fragment);
            candidates.add(new FragmentCandidate(i, fragment, fragmentScore));
        }

        if (candidates.isEmpty()) {
            return truncate(collapseSpaces(chunkText), maxCharsPerChunk);
        }

        candidates.sort(Comparator
                .comparingDouble(FragmentCandidate::score).reversed()
                .thenComparingInt(FragmentCandidate::index));

        int maxFragments = Math.max(1, maxSnippetsPerChunk);
        List<FragmentCandidate> selected = new ArrayList<>(Math.min(maxFragments, candidates.size()));
        for (FragmentCandidate candidate : candidates) {
            if (selected.size() >= maxFragments) {
                break;
            }
            if (candidate.score() <= 0.0 && !selected.isEmpty()) {
                continue;
            }
            selected.add(candidate);
        }

        if (selected.isEmpty()) {
            selected.add(candidates.get(0));
        }

        selected.sort(Comparator.comparingInt(FragmentCandidate::index));

        StringBuilder sb = new StringBuilder(Math.max(64, maxCharsPerChunk));
        for (FragmentCandidate fragment : selected) {
            if (sb.length() > 0) {
                sb.append("\n...\n");
            }
            sb.append(fragment.text());
            if (sb.length() >= maxCharsPerChunk) {
                break;
            }
        }
        return truncate(sb.toString().trim(), maxCharsPerChunk);
    }

    private int estimateTokens(List<ScoredChunk> scored) {
        if (scored == null || scored.isEmpty()) {
            return 0;
        }
        int chars = scored.stream()
                .map(ScoredChunk::effectiveText)
                .mapToInt(text -> text == null ? 0 : text.length())
                .sum();
        return Math.max(0, chars / 4);
    }

    private List<CandidateChunk> applyHybridScoring(String query,
                                                    List<CandidateChunk> semanticCandidates,
                                                    Map<Long, KnowledgeChunk> chunkById) {
        if (semanticCandidates.isEmpty()) {
            return List.of();
        }

        String normalizedQuery = normalizeSearchText(query);
        Set<String> queryTokens = tokenize(normalizedQuery);

        double semWeight = Math.max(0.0, semanticWeight);
        double lexWeight = Math.max(0.0, lexicalWeight);
        if (semWeight == 0.0 && lexWeight == 0.0) {
            semWeight = 1.0;
        }
        double weightSum = semWeight + lexWeight;
        semWeight = semWeight / weightSum;
        lexWeight = lexWeight / weightSum;

        List<CandidateChunk> rescored = new ArrayList<>(semanticCandidates.size());
        for (CandidateChunk candidate : semanticCandidates) {
            KnowledgeChunk chunk = chunkById.get(candidate.chunkId());
            if (chunk == null) {
                continue;
            }

            double lexical = lexicalScore(normalizedQuery, queryTokens, chunk.getText());
            double ownerBoost = ownerBoost(candidate.owner());
            double finalScore = semWeight * clamp01(candidate.semanticScore()) + lexWeight * lexical + ownerBoost;
            rescored.add(candidate.withScore(finalScore));
        }

        return rescored;
    }

    private double lexicalScore(String normalizedQuery, Set<String> queryTokens, String chunkText) {
        if (queryTokens.isEmpty() || !hasText(chunkText)) {
            return 0.0;
        }

        String normalizedChunk = normalizeSearchText(chunkText);
        if (normalizedChunk.isBlank()) {
            return 0.0;
        }

        Set<String> chunkTokens = tokenize(normalizedChunk);
        if (chunkTokens.isEmpty()) {
            return 0.0;
        }

        long overlap = queryTokens.stream().filter(chunkTokens::contains).count();
        if (overlap <= 0) {
            if (hasText(normalizedQuery) && normalizedChunk.contains(normalizedQuery)) {
                return clamp01(exactMatchBoost);
            }
            return 0.0;
        }

        double coverage = overlap / (double) queryTokens.size();
        double jaccard = overlap / (double) (queryTokens.size() + chunkTokens.size() - overlap);
        double phraseBoost = (hasText(normalizedQuery) && normalizedChunk.contains(normalizedQuery)) ? exactMatchBoost : 0.0;

        return clamp01((coverage * 0.75) + (jaccard * 0.25) + phraseBoost);
    }

    private List<CandidateChunk> rerankWithMmr(List<CandidateChunk> candidates, int limit, double lambda) {
        if (candidates.isEmpty() || limit <= 0) {
            return List.of();
        }

        double lambdaClamped = Math.min(1.0, Math.max(0.0, lambda));
        List<CandidateChunk> selected = new ArrayList<>();
        Set<Long> selectedIds = new LinkedHashSet<>();

        CandidateChunk first = candidates.get(0);
        selected.add(first);
        selectedIds.add(first.chunkId());

        while (selected.size() < limit && selected.size() < candidates.size()) {
            CandidateChunk best = null;
            double bestScore = Double.NEGATIVE_INFINITY;

            for (CandidateChunk candidate : candidates) {
                if (selectedIds.contains(candidate.chunkId())) {
                    continue;
                }

                double diversity = selected.stream()
                        .mapToDouble(chosen -> safeCosineUnit(candidate.embedding(), chosen.embedding()))
                        .max()
                        .orElse(0.0);

                double mmrScore = lambdaClamped * candidate.score() - (1 - lambdaClamped) * diversity;
                if (mmrScore > bestScore) {
                    bestScore = mmrScore;
                    best = candidate;
                }
            }

            if (best == null) {
                break;
            }

            selected.add(best);
            selectedIds.add(best.chunkId());
        }

        return selected;
    }

    // ----------------- PERSISTENCIA INTERNA -----------------

    private void archiveDocumentVersion(KnowledgeDocument activeDoc, Long replacementDocumentId) {
        // La version vieja deja de estar activa y sus vectores salen del indice para que retrieval no mezcle corpus.
        List<Long> chunkIds = chunkRepo.findIdsByDocumentId(activeDoc.getId());
        if (!chunkIds.isEmpty()) {
            vectorRepo.deleteByChunkIdIn(chunkIds);
            vectorIndexService.deleteChunkIds(chunkIds);
        }
        activeDoc.setActive(false);
        activeDoc.setSupersededByDocumentId(replacementDocumentId);
        docRepo.save(activeDoc);
    }

    private List<KnowledgeChunk> persistChunks(KnowledgeDocument document, List<PreparedChunkInput> chunks) {
        List<KnowledgeChunk> toPersist = new ArrayList<>(chunks.size());
        for (PreparedChunkInput input : chunks) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setDocument(document);
            chunk.setChunkIndex(input.chunkIndex());
            chunk.setText(input.text());
            chunk.setHash(input.hash());
            chunk.setTokenCount(input.tokenCount());
            chunk.setSource(input.source());
            chunk.setTags(input.tags());
            toPersist.add(chunk);
        }
        return chunkRepo.saveAll(toPersist);
    }

    private void persistVectorsAndIndex(String owner, List<KnowledgeChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }

        // Primero persistimos el respaldo durable en `vectors` y solo despues reflejamos el cambio en HNSW.
        List<String> texts = chunks.stream().map(KnowledgeChunk::getText).toList();
        List<double[]> embeddings = ollama.embedMany(texts);
        List<KnowledgeVector> vectors = new ArrayList<>(chunks.size());
        List<RagVectorIndexService.IndexedVectorRecord> indexedVectors = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            double[] raw = i < embeddings.size() ? embeddings.get(i) : new double[0];
            double[] normalized = VectorMath.normalize(raw);
            if (normalized.length == 0) {
                continue;
            }

            KnowledgeVector vector = new KnowledgeVector();
            vector.setChunk(chunk);
            vector.setEmbeddingJson(ollama.toJson(normalized));
            vector.setDimension(normalized.length);
            vectors.add(vector);

            indexedVectors.add(new RagVectorIndexService.IndexedVectorRecord(
                    chunk.getId(),
                    owner,
                    toFloatArray(normalized),
                    Instant.now()
            ));
        }

        vectorRepo.saveAll(vectors);
        vectorIndexService.indexBatch(indexedVectors);
    }

    // ----------------- HELPERS -----------------

    /**
     * Normaliza la entrada estructurada del scraper.
     * Si no llegan chunks, se usa el chunking interno para no romper clientes legacy.
     */
    private List<PreparedChunkInput> prepareChunks(List<IncomingChunk> explicitChunks,
                                                   String fallbackContent,
                                                   String documentSource,
                                                   String documentTags) {
        List<IncomingChunk> validExplicitChunks = explicitChunks == null
                ? List.of()
                : explicitChunks.stream()
                .filter(chunk -> chunk != null && hasText(chunk.text()))
                .sorted(Comparator.comparingInt(chunk -> chunk.chunkIndex() == null ? Integer.MAX_VALUE : chunk.chunkIndex()))
                .toList();

        if (!validExplicitChunks.isEmpty()) {
            List<PreparedChunkInput> prepared = new ArrayList<>(validExplicitChunks.size());
            for (int i = 0; i < validExplicitChunks.size(); i++) {
                IncomingChunk raw = validExplicitChunks.get(i);
                String text = normalizeBody(raw.text());
                String source = normalizeSource(raw.source(), documentSource);
                String tags = firstNonBlank(normalizeTags(raw.tags()), documentTags);
                String hash = normalizeHash(raw.hash(), text);
                int tokenCount = raw.tokenCount() != null && raw.tokenCount() > 0
                        ? raw.tokenCount()
                        : estimateTokenCount(text);

                prepared.add(new PreparedChunkInput(
                        i,
                        text,
                        hash,
                        tokenCount,
                        source,
                        tags
                ));
            }
            return prepared;
        }

        String body = normalizeBody(fallbackContent);
        List<String> chunkTexts = TextChunker.chunk(body, chunkSize, overlap);
        if (chunkTexts.isEmpty()) {
            throw new IllegalArgumentException("El contenido no genera chunks utiles.");
        }

        List<PreparedChunkInput> prepared = new ArrayList<>(chunkTexts.size());
        for (int i = 0; i < chunkTexts.size(); i++) {
            String text = chunkTexts.get(i);
            prepared.add(new PreparedChunkInput(
                    i,
                    text,
                    sha256(text),
                    estimateTokenCount(text),
                    documentSource,
                    documentTags
            ));
        }
        return prepared;
    }

    /**
     * La huella del documento incorpora texto y estructura de chunks.
     * Esto evita que un scraper pierda una actualizacion por tener el mismo cuerpo pero distinto particionado.
     */
    private String computeDocumentFingerprint(List<PreparedChunkInput> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        String signature = chunks.stream()
                .map(chunk -> chunk.chunkIndex()
                        + "|"
                        + fingerprint(chunk.text())
                        + "|"
                        + chunk.hash()
                        + "|"
                        + trimToEmpty(chunk.source())
                        + "|"
                        + trimToEmpty(chunk.tags()))
                .collect(Collectors.joining("||"));
        return sha256(signature);
    }

    /**
     * Reconstruye el texto logico de un documento desde su tabla append-only de chunks.
     * Este metodo es la referencia cuando mantenimiento o prompts necesiten ver el documento entero.
     */
    public static String rebuildDocumentText(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        return chunks.stream()
                .filter(chunk -> chunk != null && hasText(chunk.getText()))
                .sorted(Comparator.comparingInt(KnowledgeChunk::getChunkIndex))
                .map(KnowledgeChunk::getText)
                .collect(Collectors.joining("\n\n"))
                .trim();
    }

    private static float[] toFloatArray(double[] vector) {
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = (float) vector[i];
        }
        return out;
    }

    private static double normalizeSemanticScore(double rawScore) {
        if (!Double.isFinite(rawScore)) {
            return 0.0;
        }
        if (rawScore < 0.0) {
            return clamp01((rawScore + 1.0) / 2.0);
        }
        return clamp01(rawScore);
    }

    private static double safeCosineUnit(double[] a, double[] b) {
        double v = VectorMath.cosineUnit(a, b);
        return Double.isFinite(v) && v > -1.0 ? v : 0.0;
    }

    private static Set<String> tokenize(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return Set.of();
        }

        String[] pieces = normalizedText.split("\\s+");
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : pieces) {
            String t = token == null ? "" : token.trim();
            if (t.length() < 3) {
                continue;
            }
            if (STOPWORDS.contains(t)) {
                continue;
            }
            tokens.add(t);
        }
        return tokens;
    }

    private static String normalizeSearchText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String normalizeBody(String content) {
        String body = trimToEmpty(content);
        if (body.isBlank()) {
            throw new IllegalArgumentException("Contenido vacio");
        }
        return body;
    }

    private static String normalizeOwner(String owner) {
        String o = trimToEmpty(owner);
        return o.isBlank() ? GLOBAL_OWNER : o;
    }

    private static List<String> normalizeOwners(List<String> owners) {
        if (owners == null || owners.isEmpty()) {
            return List.of(GLOBAL_OWNER);
        }

        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String owner : owners) {
            set.add(normalizeOwner(owner));
        }
        set.add(GLOBAL_OWNER);
        return List.copyOf(set);
    }

    private static String normalizeTitle(String title) {
        String t = trimToEmpty(title).replaceAll("\\s+", " ");
        if (t.isBlank()) {
            throw new IllegalArgumentException("Titulo vacio");
        }
        if (t.length() > 200) {
            return t.substring(0, 200);
        }
        return t;
    }

    private static String normalizeSource(String source, String fallback) {
        String clean = trimToEmpty(source).replaceAll("\\s+", " ");
        if (clean.isBlank()) {
            return fallback;
        }
        return clean.length() > 160 ? clean.substring(0, 160) : clean;
    }

    private static String normalizeReferenceUrl(String referenceUrl) {
        String clean = trimToEmpty(referenceUrl);
        if (clean.isBlank()) {
            return null;
        }
        return clean.length() > 1000 ? clean.substring(0, 1000) : clean;
    }

    private static String normalizeTags(String tags) {
        String clean = trimToEmpty(tags).replaceAll("\\s+", " ");
        if (clean.isBlank()) {
            return null;
        }
        return clean.length() > 1000 ? clean.substring(0, 1000) : clean;
    }

    private static String normalizeHash(String hash, String text) {
        String clean = trimToEmpty(hash).toLowerCase(Locale.ROOT);
        if (clean.isBlank()) {
            return sha256(text);
        }
        return clean.length() > 128 ? clean.substring(0, 128) : clean;
    }

    /**
     * El fingerprint persistido del documento debe ser compacto y estable.
     * Guardamos hash del contenido normalizado para evitar desbordar la columna y para comparar versiones.
     */
    private static String fingerprint(String text) {
        String normalized = normalizeSearchText(text);
        if (normalized.isBlank()) {
            return "";
        }
        return sha256(normalized);
    }

    private static String collapseSpaces(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String text, int maxChars) {
        if (!hasText(text)) {
            return "";
        }
        int limit = Math.max(1, maxChars);
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit).trim();
    }

    private static int estimateTokenCount(String text) {
        if (!hasText(text)) {
            return 0;
        }
        String[] parts = text.trim().split("\\s+");
        return Math.max(0, parts.length);
    }

    /**
     * Devuelve el embedding normalizado para la query dada, usando cache si esta disponible.
     * El cache evita llamar a Ollama para la misma consulta dentro de la ventana de TTL.
     */
    private double[] getCachedEmbedding(String query) {
        if (query == null || query.isBlank()) {
            return new double[0];
        }
        String key = normalizeSearchText(query);
        if (key.isBlank()) {
            return VectorMath.normalize(ollama.embedOne(query));
        }
        long now = System.currentTimeMillis();
        CachedEmbedding cached = embeddingCache.get(key);
        if (cached != null && cached.expiresAt() > now) {
            return cached.vec();
        }
        double[] fresh = VectorMath.normalize(ollama.embedOne(query));
        if (fresh.length > 0) {
            embeddingCache.put(key, new CachedEmbedding(fresh, now + EMBEDDING_CACHE_TTL_MS));
            embeddingCacheOrder.addLast(key);
            // Eviccion FIFO cuando se supera el maximo.
            while (embeddingCacheOrder.size() > EMBEDDING_CACHE_MAX) {
                String evicted = embeddingCacheOrder.pollFirst();
                if (evicted != null) {
                    embeddingCache.remove(evicted);
                }
            }
        }
        return fresh;
    }

    private static double nanosToMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

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

    private double ownerBoost(String owner) {
        if (!hasText(owner)) {
            return 0.0;
        }
        return GLOBAL_OWNER.equalsIgnoreCase(owner) ? globalOwnerBoost : userOwnerBoost;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(trimToEmpty(text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible.", e);
        }
    }

    private record FragmentCandidate(int index, String text, double score) {
    }

    private record CandidateChunk(Long chunkId,
                                  String owner,
                                  double semanticScore,
                                  double score,
                                  double[] embedding) {
        private CandidateChunk withScore(double newScore) {
            return new CandidateChunk(chunkId, owner, semanticScore, newScore, embedding);
        }
    }

    /**
     * DTO interno del servicio para representar chunks ya preparados desde el endpoint.
     */
    public record IncomingChunk(Integer chunkIndex,
                                String text,
                                String hash,
                                Integer tokenCount,
                                String source,
                                String tags) {
    }

    private record PreparedChunkInput(int chunkIndex,
                                      String text,
                                      String hash,
                                      int tokenCount,
                                      String source,
                                      String tags) {
    }

    /**
     * Agrupa el resultado bruto del retrieval y el subconjunto comprimido que se enviara al prompt.
     */
    public record RetrievalResult(List<ScoredChunk> retrievedChunks,
                                  List<ScoredChunk> contextChunks,
                                  RetrievalStats stats) {

        public RetrievalResult {
            retrievedChunks = retrievedChunks == null ? List.of() : List.copyOf(retrievedChunks);
            contextChunks = contextChunks == null ? List.of() : List.copyOf(contextChunks);
            stats = stats == null ? RetrievalStats.empty(List.of(), 0.0, 0, 0.0) : stats;
        }

        public boolean hasEvidence() {
            return !contextChunks.isEmpty();
        }

        public static RetrievalResult empty(List<String> owners,
                                            double queryEmbeddingTimeMs,
                                            int requestedTopK,
                                            double evidenceThreshold) {
            return new RetrievalResult(
                    List.of(),
                    List.of(),
                    RetrievalStats.empty(owners, queryEmbeddingTimeMs, requestedTopK, evidenceThreshold)
            );
        }
    }

    /**
     * Telemetria minima para entender que paso en cada retrieval.
     */
    public record RetrievalStats(List<String> owners,
                                 double queryEmbeddingTimeMs,
                                 int requestedTopK,
                                 int topKReturned,
                                 double maxSimilarity,
                                 double avgSimilarity,
                                 double evidenceThreshold,
                                 int contextTokens,
                                 List<Long> chunksUsedIds,
                                 List<String> sourceDocs) {

        public RetrievalStats {
            owners = owners == null ? List.of() : List.copyOf(owners);
            queryEmbeddingTimeMs = Math.max(0.0, queryEmbeddingTimeMs);
            requestedTopK = Math.max(0, requestedTopK);
            topKReturned = Math.max(0, topKReturned);
            maxSimilarity = clamp01(maxSimilarity);
            avgSimilarity = clamp01(avgSimilarity);
            evidenceThreshold = clamp01(evidenceThreshold);
            contextTokens = Math.max(0, contextTokens);
            chunksUsedIds = chunksUsedIds == null ? List.of() : List.copyOf(chunksUsedIds);
            sourceDocs = sourceDocs == null ? List.of() : List.copyOf(sourceDocs);
        }

        public static RetrievalStats empty(List<String> owners,
                                           double queryEmbeddingTimeMs,
                                           int requestedTopK,
                                           double evidenceThreshold) {
            return new RetrievalStats(
                    owners,
                    queryEmbeddingTimeMs,
                    requestedTopK,
                    0,
                    0.0,
                    0.0,
                    evidenceThreshold,
                    0,
                    List.of(),
                    List.of()
            );
        }
    }

    /**
     * Chunk recuperado junto con el texto final que entrara al prompt.
     */
    public record ScoredChunk(KnowledgeChunk chunk, double score, String promptText) {

        public ScoredChunk(KnowledgeChunk chunk, double score) {
            this(chunk, score, defaultPromptText(chunk));
        }

        public ScoredChunk {
            promptText = hasText(promptText) ? promptText : defaultPromptText(chunk);
        }

        public String effectiveText() {
            return hasText(promptText) ? promptText : defaultPromptText(chunk);
        }

        public ScoredChunk withPromptText(String newPromptText) {
            return new ScoredChunk(chunk, score, newPromptText);
        }

        private static String defaultPromptText(KnowledgeChunk chunk) {
            return chunk == null || chunk.getText() == null ? "" : chunk.getText();
        }
    }
}
