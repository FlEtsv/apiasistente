package com.example.apiasistente.service;

import com.example.apiasistente.model.dto.RagContextStatsDto;
import com.example.apiasistente.model.dto.SourceDto;
import com.example.apiasistente.model.entity.KnowledgeChunk;
import com.example.apiasistente.model.entity.KnowledgeDocument;
import com.example.apiasistente.repository.KnowledgeChunkRepository;
import com.example.apiasistente.repository.KnowledgeDocumentRepository;
import com.example.apiasistente.util.TextChunker;
import com.example.apiasistente.util.VectorMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class RagService {

    public static final String GLOBAL_OWNER = "global";
    private static final int EMBEDDING_PAGE_SIZE = 500;
    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private static final Set<String> STOPWORDS = Set.of(
            "de", "la", "el", "los", "las", "y", "o", "u", "en", "por", "para", "con", "sin", "del", "al",
            "que", "como", "donde", "cuando", "cual", "cuales", "quien", "quienes", "porque", "sobre",
            "the", "and", "or", "for", "with", "from", "this", "that", "those", "these", "into", "your", "you"
    );

    private final KnowledgeDocumentRepository docRepo;
    private final KnowledgeChunkRepository chunkRepo;
    private final OllamaClient ollama;

    @Value("${rag.top-k:5}")
    private int topK;

    @Value("${rag.chunk.size:900}")
    private int chunkSize;

    @Value("${rag.chunk.overlap:150}")
    private int overlap;

    @Value("${rag.cache.enabled:true}")
    private boolean cacheEnabled;

    @Value("${rag.rerank.candidates:12}")
    private int rerankCandidates;

    @Value("${rag.rerank.lambda:0.65}")
    private double rerankLambda;

    @Value("${rag.score.min:-1.0}")
    private double minScore;

    @Value("${rag.hybrid.semantic-weight:0.80}")
    private double semanticWeight;

    @Value("${rag.hybrid.lexical-weight:0.20}")
    private double lexicalWeight;

    @Value("${rag.hybrid.exact-match-boost:0.12}")
    private double exactMatchBoost;

    @Value("${rag.owner.min-candidates-per-owner:10}")
    private int minCandidatesPerOwner;

    @Value("${rag.owner.global-boost:0.03}")
    private double globalOwnerBoost;

    @Value("${rag.owner.user-boost:0.05}")
    private double userOwnerBoost;

    public RagService(KnowledgeDocumentRepository docRepo, KnowledgeChunkRepository chunkRepo, OllamaClient ollama) {
        this.docRepo = docRepo;
        this.chunkRepo = chunkRepo;
        this.ollama = ollama;
    }

    /**
     * Cache global de embeddings normalizados por chunkId.
     */
    private final Map<Long, double[]> embeddingCache = new ConcurrentHashMap<>();

    /**
     * Cache por owner para evitar leer MySQL en cada consulta de chat.
     */
    private final Map<String, OwnerCorpusCache> ownerCorpusCache = new ConcurrentHashMap<>();

    // ----------------- UPSERT -----------------

    @Transactional
    public KnowledgeDocument upsertDocument(String title, String content) {
        return upsertDocumentForOwner(GLOBAL_OWNER, title, content);
    }

    @Transactional
    public KnowledgeDocument upsertDocumentForOwner(String owner, String title, String content) {

        String normalizedOwner = normalizeOwner(owner);
        String normalizedTitle = normalizeTitle(title);
        String body = (content == null) ? "" : content;

        if (body.isBlank()) {
            throw new IllegalArgumentException("Contenido vacio");
        }

        KnowledgeDocument doc = docRepo.findFirstByOwnerAndTitleIgnoreCase(normalizedOwner, normalizedTitle)
                .orElseGet(KnowledgeDocument::new);

        boolean isNew = (doc.getId() == null);

        doc.setOwner(normalizedOwner);
        doc.setTitle(normalizedTitle);
        doc.setContent(body);
        doc = docRepo.save(doc);

        List<Long> oldIds = List.of();
        if (!isNew) {
            oldIds = chunkRepo.findIdsByDocumentId(doc.getId());
            chunkRepo.deleteByDocument_Id(doc.getId());
            if (cacheEnabled && !oldIds.isEmpty()) {
                removeChunkIdsFromCaches(normalizedOwner, oldIds);
            }
        }

        List<String> chunks = TextChunker.chunk(body, chunkSize, overlap);
        if (chunks.isEmpty()) {
            log.debug("RAG upsert sin chunks para owner='{}' title='{}'", normalizedOwner, normalizedTitle);
            return doc;
        }

        List<double[]> embeddings = ollama.embedMany(chunks);
        List<KnowledgeChunk> toPersist = new ArrayList<>(chunks.size());
        List<double[]> normalizedEmbeddings = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            double[] rawEmbedding = (i < embeddings.size()) ? embeddings.get(i) : new double[0];
            double[] normalizedEmbedding = VectorMath.normalize(rawEmbedding);
            normalizedEmbeddings.add(normalizedEmbedding);

            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setDocument(doc);
            chunk.setChunkIndex(i);
            chunk.setText(chunks.get(i));
            chunk.setEmbeddingJson(ollama.toJson(normalizedEmbedding));
            toPersist.add(chunk);
        }

        List<KnowledgeChunk> persisted = chunkRepo.saveAll(toPersist);

        if (cacheEnabled) {
            upsertChunkEmbeddingsInCache(normalizedOwner, persisted, normalizedEmbeddings);
        }

        log.debug(
                "RAG upsert owner='{}' title='{}' chunks={} replaced={}",
                normalizedOwner,
                normalizedTitle,
                persisted.size(),
                oldIds.size()
        );
        return doc;
    }

    @Transactional
    public KnowledgeDocument storeMemory(String username, String title, String content) {

        String user = (username == null || username.isBlank()) ? "unknown" : username.trim();

        String cleanTitle = (title == null) ? "" : title.trim();
        if (cleanTitle.isBlank()) {
            cleanTitle = "Memoria/" + user + "/" + Instant.now();
        }

        return upsertDocumentForOwner(user, cleanTitle, content);
    }

    // ----------------- RETRIEVAL -----------------

    public List<ScoredChunk> retrieveTopK(String query) {
        return retrieveTopKForOwners(query, List.of(GLOBAL_OWNER));
    }

    public List<ScoredChunk> retrieveTopKForOwnerOrGlobal(String query, String owner) {
        String normalizedOwner = normalizeOwner(owner);
        if (GLOBAL_OWNER.equalsIgnoreCase(normalizedOwner)) {
            return retrieveTopKForOwners(query, List.of(GLOBAL_OWNER));
        }
        return retrieveTopKForOwners(query, List.of(GLOBAL_OWNER, normalizedOwner));
    }

    /**
     * Retrieval que combina global + owner + owner aislado (ej: key:{id}|user:{externalUser}).
     */
    public List<ScoredChunk> retrieveTopKForOwnerScopedAndGlobal(String query,
                                                                  String owner,
                                                                  String scopedOwner) {
        List<String> owners = new ArrayList<>();
        owners.add(GLOBAL_OWNER);

        String normalizedOwner = normalizeOwner(owner);
        if (!GLOBAL_OWNER.equalsIgnoreCase(normalizedOwner)) {
            owners.add(normalizedOwner);
        }

        if (hasText(scopedOwner)) {
            owners.add(scopedOwner.trim());
        }

        return retrieveTopKForOwners(query, owners);
    }

    public RagContextStatsDto contextStatsForOwnerOrGlobal(String owner) {
        String normalizedOwner = normalizeOwner(owner);
        List<String> owners = normalizeOwners(List.of(normalizedOwner));

        long totalDocuments = docRepo.countByOwnerIn(owners);
        long totalChunks = chunkRepo.countByDocument_OwnerIn(owners);

        long globalDocuments = docRepo.countByOwner(GLOBAL_OWNER);
        long globalChunks = chunkRepo.countByDocument_Owner(GLOBAL_OWNER);

        long ownerDocuments = GLOBAL_OWNER.equals(normalizedOwner) ? 0 : docRepo.countByOwner(normalizedOwner);
        long ownerChunks = GLOBAL_OWNER.equals(normalizedOwner) ? 0 : chunkRepo.countByDocument_Owner(normalizedOwner);

        Instant lastUpdatedAt = docRepo.findLastUpdateAtByOwners(owners);

        return new RagContextStatsDto(
                normalizedOwner,
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

    /**
     * Retrieval filtrado por owners con estrategia hibrida (semantica + lexical) y balance por owner.
     */
    public List<ScoredChunk> retrieveTopKForOwners(String query, List<String> owners) {
        double[] queryEmbedding = VectorMath.normalize(ollama.embedOne(query));
        if (queryEmbedding.length == 0) {
            return List.of();
        }

        List<String> ownersClean = normalizeOwners(owners);
        int retrievalTopK = Math.max(1, topK);
        int baseCandidatesLimit = Math.max(retrievalTopK, rerankCandidates);
        int perOwnerLimit = Math.max(baseCandidatesLimit, Math.max(1, minCandidatesPerOwner));
        int mergedLimit = Math.max(baseCandidatesLimit, perOwnerLimit * ownersClean.size());

        long startNanos = System.nanoTime();

        List<CandidateChunk> semanticCandidates = cacheEnabled
                ? retrieveSemanticCandidatesFromOwnerCaches(queryEmbedding, ownersClean, perOwnerLimit, mergedLimit)
                : retrieveSemanticCandidatesFromDatabase(queryEmbedding, ownersClean, perOwnerLimit, mergedLimit);

        if (semanticCandidates.isEmpty()) {
            return List.of();
        }

        List<Long> candidateIds = semanticCandidates.stream().map(CandidateChunk::chunkId).toList();
        List<KnowledgeChunk> candidateChunks = chunkRepo.findWithDocumentByIdIn(candidateIds);
        Map<Long, KnowledgeChunk> chunkById = candidateChunks.stream()
                .collect(Collectors.toMap(KnowledgeChunk::getId, c -> c));

        List<CandidateChunk> hybridCandidates = applyHybridScoring(query, semanticCandidates, chunkById);
        if (hybridCandidates.isEmpty()) {
            return List.of();
        }

        hybridCandidates.sort(Comparator.comparingDouble(CandidateChunk::score).reversed());

        List<CandidateChunk> reranked = rerankWithMmr(hybridCandidates, retrievalTopK, rerankLambda);
        if (reranked.isEmpty()) {
            return List.of();
        }

        List<ScoredChunk> result = reranked.stream()
                .map(sc -> new ScoredChunk(chunkById.get(sc.chunkId()), sc.score()))
                .filter(sc -> sc.chunk() != null)
                .toList();

        if (log.isDebugEnabled()) {
            double elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0;
            log.debug(
                    "RAG retrieve owners={} semanticCandidates={} hybridCandidates={} selected={} topK={} elapsedMs={}",
                    ownersClean,
                    semanticCandidates.size(),
                    hybridCandidates.size(),
                    result.size(),
                    retrievalTopK,
                    String.format(Locale.US, "%.2f", elapsedMs)
            );
        }

        return result;
    }

    public List<SourceDto> toSourceDtos(List<ScoredChunk> scored) {
        return scored.stream().map(sc -> {
            KnowledgeChunk c = sc.chunk();
            KnowledgeDocument d = c.getDocument();
            String text = c.getText();
            String snippet = text.length() > 220 ? text.substring(0, 220) + "..." : text;
            return new SourceDto(c.getId(), d.getId(), d.getTitle(), sc.score(), snippet);
        }).toList();
    }

    private List<CandidateChunk> retrieveSemanticCandidatesFromOwnerCaches(double[] queryEmbedding,
                                                                            List<String> owners,
                                                                            int perOwnerLimit,
                                                                            int mergedLimit) {
        List<CandidateChunk> merged = new ArrayList<>();
        for (String owner : owners) {
            merged.addAll(topSemanticCandidatesForOwnerFromCache(queryEmbedding, owner, perOwnerLimit));
        }
        return dedupeAndSortCandidates(merged, mergedLimit);
    }

    private List<CandidateChunk> retrieveSemanticCandidatesFromDatabase(double[] queryEmbedding,
                                                                        List<String> owners,
                                                                        int perOwnerLimit,
                                                                        int mergedLimit) {
        List<CandidateChunk> merged = new ArrayList<>();
        for (String owner : owners) {
            merged.addAll(topSemanticCandidatesForOwnerFromDatabase(queryEmbedding, owner, perOwnerLimit));
        }
        return dedupeAndSortCandidates(merged, mergedLimit);
    }

    private List<CandidateChunk> topSemanticCandidatesForOwnerFromCache(double[] queryEmbedding,
                                                                         String owner,
                                                                         int perOwnerLimit) {
        PriorityQueue<CandidateChunk> heap = new PriorityQueue<>(Comparator.comparingDouble(CandidateChunk::score));
        OwnerCorpusCache corpus = ensureOwnerCorpusLoaded(owner);

        for (var entry : corpus.embeddingsByChunkId().entrySet()) {
            double[] embedding = entry.getValue();
            double semanticScore = VectorMath.cosineUnit(queryEmbedding, embedding);
            if (semanticScore < minScore) {
                continue;
            }
            offerCandidate(
                    heap,
                    new CandidateChunk(entry.getKey(), owner, semanticScore, semanticScore, embedding),
                    perOwnerLimit
            );
        }

        return toSortedCandidateList(heap);
    }

    private List<CandidateChunk> topSemanticCandidatesForOwnerFromDatabase(double[] queryEmbedding,
                                                                            String owner,
                                                                            int perOwnerLimit) {
        PriorityQueue<CandidateChunk> heap = new PriorityQueue<>(Comparator.comparingDouble(CandidateChunk::score));

        int page = 0;
        while (true) {
            var slice = chunkRepo.findEmbeddingPageByOwners(List.of(owner), PageRequest.of(page, EMBEDDING_PAGE_SIZE));
            if (slice.isEmpty()) {
                break;
            }

            for (var view : slice.getContent()) {
                double[] embedding = VectorMath.normalize(ollama.fromJson(view.getEmbeddingJson()));
                if (embedding.length == 0) {
                    continue;
                }

                double semanticScore = VectorMath.cosineUnit(queryEmbedding, embedding);
                if (semanticScore < minScore) {
                    continue;
                }

                offerCandidate(
                        heap,
                        new CandidateChunk(view.getId(), owner, semanticScore, semanticScore, embedding),
                        perOwnerLimit
                );
            }

            if (!slice.hasNext()) {
                break;
            }
            page++;
        }

        return toSortedCandidateList(heap);
    }

    private List<CandidateChunk> dedupeAndSortCandidates(List<CandidateChunk> candidates, int maxTotal) {
        Map<Long, CandidateChunk> bestByChunk = new ConcurrentHashMap<>();
        for (CandidateChunk candidate : candidates) {
            bestByChunk.merge(
                    candidate.chunkId(),
                    candidate,
                    (current, incoming) -> incoming.score() > current.score() ? incoming : current
            );
        }

        List<CandidateChunk> deduped = new ArrayList<>(bestByChunk.values());
        deduped.sort(Comparator.comparingDouble(CandidateChunk::score).reversed());

        if (maxTotal > 0 && deduped.size() > maxTotal) {
            return new ArrayList<>(deduped.subList(0, maxTotal));
        }
        return deduped;
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

            double semanticNormalized = normalizeCosineScore(candidate.semanticScore());
            double lexical = lexicalScore(normalizedQuery, queryTokens, chunk.getText());
            double ownerBoost = ownerBoost(candidate.owner());

            double finalScore = semWeight * semanticNormalized + lexWeight * lexical + ownerBoost;
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
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized;
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

    private static double normalizeCosineScore(double cosine) {
        if (!Double.isFinite(cosine)) {
            return 0.0;
        }
        double normalized = (cosine + 1.0) / 2.0;
        return clamp01(normalized);
    }

    private static void offerCandidate(PriorityQueue<CandidateChunk> heap,
                                       CandidateChunk candidate,
                                       int maxCandidates) {
        if (heap.size() < maxCandidates) {
            heap.add(candidate);
            return;
        }

        CandidateChunk min = heap.peek();
        if (min != null && candidate.score() > min.score()) {
            heap.poll();
            heap.add(candidate);
        }
    }

    private static List<CandidateChunk> toSortedCandidateList(PriorityQueue<CandidateChunk> heap) {
        List<CandidateChunk> candidates = new ArrayList<>(heap);
        candidates.sort(Comparator.comparingDouble(CandidateChunk::score).reversed());
        return candidates;
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

    private double safeCosineUnit(double[] a, double[] b) {
        double v = VectorMath.cosineUnit(a, b);
        return Double.isFinite(v) && v > -1.0 ? v : 0.0;
    }

    private OwnerCorpusCache ensureOwnerCorpusLoaded(String owner) {
        OwnerCorpusCache corpus = ownerCorpusCache.computeIfAbsent(owner, key -> new OwnerCorpusCache());
        if (corpus.loaded()) {
            return corpus;
        }

        synchronized (corpus) {
            if (corpus.loaded()) {
                return corpus;
            }

            int page = 0;
            while (true) {
                var slice = chunkRepo.findEmbeddingPageByOwners(List.of(owner), PageRequest.of(page, EMBEDDING_PAGE_SIZE));
                if (slice.isEmpty()) {
                    break;
                }

                for (var view : slice.getContent()) {
                    double[] embedding = embeddingCache.computeIfAbsent(
                            view.getId(),
                            id -> normalizeEmbeddingJson(view.getEmbeddingJson())
                    );
                    if (embedding.length > 0) {
                        corpus.embeddingsByChunkId().put(view.getId(), embedding);
                    }
                }

                if (!slice.hasNext()) {
                    break;
                }
                page++;
            }

            corpus.setLoaded(true);
        }

        return corpus;
    }

    private void removeChunkIdsFromCaches(String owner, List<Long> ids) {
        for (Long id : ids) {
            embeddingCache.remove(id);
        }

        OwnerCorpusCache ownerCache = ownerCorpusCache.get(owner);
        if (ownerCache != null && ownerCache.loaded()) {
            for (Long id : ids) {
                ownerCache.embeddingsByChunkId().remove(id);
            }
        }
    }

    private void upsertChunkEmbeddingsInCache(String owner,
                                              List<KnowledgeChunk> persisted,
                                              List<double[]> normalizedEmbeddings) {
        OwnerCorpusCache ownerCache = ownerCorpusCache.get(owner);

        for (int i = 0; i < persisted.size(); i++) {
            KnowledgeChunk chunk = persisted.get(i);
            double[] embedding = i < normalizedEmbeddings.size() ? normalizedEmbeddings.get(i) : new double[0];
            if (embedding.length == 0 || chunk.getId() == null) {
                continue;
            }

            embeddingCache.put(chunk.getId(), embedding);

            if (ownerCache != null && ownerCache.loaded()) {
                ownerCache.embeddingsByChunkId().put(chunk.getId(), embedding);
            }
        }
    }

    private double[] normalizeEmbeddingJson(String embeddingJson) {
        return VectorMath.normalize(ollama.fromJson(embeddingJson));
    }

    private static final class OwnerCorpusCache {
        private final Map<Long, double[]> embeddingsByChunkId = new ConcurrentHashMap<>();
        private volatile boolean loaded;

        private Map<Long, double[]> embeddingsByChunkId() {
            return embeddingsByChunkId;
        }

        private boolean loaded() {
            return loaded;
        }

        private void setLoaded(boolean loaded) {
            this.loaded = loaded;
        }
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

    public record ScoredChunk(KnowledgeChunk chunk, double score) {
    }

    // ----------------- Helpers -----------------

    private static String normalizeOwner(String owner) {
        String o = (owner == null) ? "" : owner.trim();
        return o.isBlank() ? GLOBAL_OWNER : o;
    }

    private static List<String> normalizeOwners(List<String> owners) {
        if (owners == null || owners.isEmpty()) {
            return List.of(GLOBAL_OWNER);
        }

        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String owner : owners) {
            String normalized = normalizeOwner(owner);
            if (normalized.contains(" ")) {
                normalized = normalized.trim();
            }
            set.add(normalized);
        }
        set.add(GLOBAL_OWNER);
        return List.copyOf(set);
    }

    private static String normalizeTitle(String title) {
        String t = (title == null) ? "" : title.trim();
        if (t.isBlank()) {
            throw new IllegalArgumentException("Titulo vacio");
        }

        t = t.replaceAll("\\s+", " ");
        if (t.length() > 200) {
            t = t.substring(0, 200);
        }
        return t;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
