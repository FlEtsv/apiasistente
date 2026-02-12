package com.example.apiasistente.service;

import com.example.apiasistente.model.dto.SourceDto;
import com.example.apiasistente.model.entity.KnowledgeChunk;
import com.example.apiasistente.model.entity.KnowledgeDocument;
import com.example.apiasistente.repository.KnowledgeChunkRepository;
import com.example.apiasistente.repository.KnowledgeDocumentRepository;
import com.example.apiasistente.util.TextChunker;
import com.example.apiasistente.util.VectorMath;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class RagService {

    public static final String GLOBAL_OWNER = "global";

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

    public RagService(KnowledgeDocumentRepository docRepo, KnowledgeChunkRepository chunkRepo, OllamaClient ollama) {
        this.docRepo = docRepo;
        this.chunkRepo = chunkRepo;
        this.ollama = ollama;
    }

    private final Map<Long, double[]> embeddingCache = new ConcurrentHashMap<>();

    // ----------------- UPSERT -----------------

    @Transactional
    public KnowledgeDocument upsertDocument(String title, String content) {
        return upsertDocumentForOwner(GLOBAL_OWNER, title, content);
    }

    @Transactional
    public KnowledgeDocument upsertDocumentForOwner(String owner, String title, String content) {

        String o = normalizeOwner(owner); //devuelve global.owner por defecto para contexto de toda la app
        String t = normalizeTitle(title);
        String body = (content == null) ? "" : content;

        // Si no hay contenido, permitimos crear doc vacío? Mejor NO,
        if (body.isBlank()) {
            throw new IllegalArgumentException("Contenido vacío");
        }

        // 1) Buscar doc por (owner,title) -> update si existe
        KnowledgeDocument doc = docRepo.findFirstByOwnerAndTitleIgnoreCase(o, t)
                .orElseGet(KnowledgeDocument::new);

        boolean isNew = (doc.getId() == null);

        doc.setOwner(o);
        doc.setTitle(t);
        doc.setContent(body);
        doc = docRepo.save(doc);

        // 2) Si ya existía: borrar chunks antiguos + limpiar cache
        if (!isNew) {
            if (cacheEnabled) {
                List<Long> oldIds = chunkRepo.findIdsByDocumentId(doc.getId());
                for (Long id : oldIds) embeddingCache.remove(id);
            }
            chunkRepo.deleteByDocument_Id(doc.getId());
        }

        // 3) Chunk + embeddings
        List<String> chunks = TextChunker.chunk(body, chunkSize, overlap);
        if (chunks.isEmpty()) return doc;

        List<double[]> embeddings = ollama.embedMany(chunks);

        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk c = new KnowledgeChunk();
            c.setDocument(doc);
            c.setChunkIndex(i);
            c.setText(chunks.get(i));

            double[] emb = (i < embeddings.size()) ? embeddings.get(i) : new double[0];
            c.setEmbeddingJson(ollama.toJson(emb));

            c = chunkRepo.save(c);

            if (cacheEnabled && emb.length > 0) {
                embeddingCache.put(c.getId(), emb);
            }
        }

        return doc;
    }

    @Transactional
    public KnowledgeDocument storeMemory(String username, String title, String content) {

        String user = (username == null || username.isBlank()) ? "unknown" : username.trim();

        String cleanTitle = (title == null) ? "" : title.trim();
        if (cleanTitle.isBlank()) {
            cleanTitle = "Memoria/" + user + "/" + Instant.now();
        }

        // Owner = username (memoria privada para ese user/app)
        return upsertDocumentForOwner(user, cleanTitle, content);
    }

    // ----------------- RETRIEVAL -----------------

    public List<ScoredChunk> retrieveTopK(String query) {
        // Mantiene comportamiento viejo: solo docs "global"
        return retrieveTopKForOwners(query, List.of(GLOBAL_OWNER));
    }

    public List<ScoredChunk> retrieveTopKForOwnerOrGlobal(String query, String owner) {
        String o = normalizeOwner(owner);
        if (GLOBAL_OWNER.equalsIgnoreCase(o)) {
            return retrieveTopKForOwners(query, List.of(GLOBAL_OWNER));
        }
        return retrieveTopKForOwners(query, List.of(GLOBAL_OWNER, o));
    }

    /**
     * Retrieval filtrado por owners.
     */
    public List<ScoredChunk> retrieveTopKForOwners(String query, List<String> owners) {
        double[] q = ollama.embedOne(query);
        if (q.length == 0) return List.of();

        List<String> ownersClean = normalizeOwners(owners);
        int candidatesLimit = Math.max(topK, rerankCandidates);

        PriorityQueue<CandidateChunk> heap =
                new PriorityQueue<>(Comparator.comparingDouble(CandidateChunk::score)); // min-heap

        int page = 0;
        int size = 500;

        while (true) {
            var slice = chunkRepo.findEmbeddingPageByOwners(ownersClean, PageRequest.of(page, size));
            if (slice.isEmpty()) break;

            for (var view : slice.getContent()) {
                double[] v = cacheEnabled
                        ? embeddingCache.computeIfAbsent(view.getId(), key -> ollama.fromJson(view.getEmbeddingJson()))
                        : ollama.fromJson(view.getEmbeddingJson());

                double score = VectorMath.cosine(q, v);
                if (score < minScore) {
                    continue;
                }

                CandidateChunk candidate = new CandidateChunk(view.getId(), score, v);
                if (heap.size() < candidatesLimit) {
                    heap.add(candidate);
                } else if (score > heap.peek().score()) {
                    heap.poll();
                    heap.add(candidate);
                }
            }

            if (!slice.hasNext()) break;
            page++;
        }

        List<CandidateChunk> candidates = new ArrayList<>(heap);
        candidates.sort(Comparator.comparingDouble(CandidateChunk::score).reversed());
        if (candidates.isEmpty()) return List.of();

        List<CandidateChunk> reranked = rerankWithMmr(candidates, topK, rerankLambda);
        if (reranked.isEmpty()) return List.of();

        List<Long> ids = reranked.stream().map(CandidateChunk::chunkId).toList();
        List<KnowledgeChunk> chunks = chunkRepo.findWithDocumentByIdIn(ids);

        Map<Long, KnowledgeChunk> chunkById = chunks.stream()
                .collect(Collectors.toMap(KnowledgeChunk::getId, c -> c));

        return reranked.stream()
                .map(sc -> new ScoredChunk(chunkById.get(sc.chunkId()), sc.score()))
                .filter(sc -> sc.chunk() != null)
                .toList();
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
                        .mapToDouble(chosen -> safeCosine(candidate.embedding(), chosen.embedding()))
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

    private double safeCosine(double[] a, double[] b) {
        double v = VectorMath.cosine(a, b);
        return Double.isFinite(v) && v > -1.0 ? v : 0.0;
    }

    private record CandidateChunk(Long chunkId, double score, double[] embedding) {}
    public record ScoredChunk(KnowledgeChunk chunk, double score) {}

    // ----------------- Helpers -----------------

    private static String normalizeOwner(String owner) {
        String o = (owner == null) ? "" : owner.trim();
        return o.isBlank() ? GLOBAL_OWNER : o;
    }

    private static List<String> normalizeOwners(List<String> owners) {
        if (owners == null || owners.isEmpty()) return List.of(GLOBAL_OWNER);

        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String o : owners) {
            String x = normalizeOwner(o);
            set.add(x);
        }
        // Asegura global siempre (si quieres)
        set.add(GLOBAL_OWNER);
        return List.copyOf(set);
    }

    private static String normalizeTitle(String title) {
        String t = (title == null) ? "" : title.trim();
        if (t.isBlank()) throw new IllegalArgumentException("Título vacío");

        t = t.replaceAll("\\s+", " ");
        if (t.length() > 200) t = t.substring(0, 200);
        return t;
    }
}
