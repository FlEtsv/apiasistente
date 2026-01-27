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

        String o = normalizeOwner(owner);
        String t = normalizeTitle(title);
        String body = (content == null) ? "" : content;

        // Si no hay contenido, permitimos crear doc vacío? Mejor NO.
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

        PriorityQueue<ScoredChunkId> heap =
                new PriorityQueue<>(Comparator.comparingDouble(ScoredChunkId::score)); // min-heap

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

                if (heap.size() < topK) {
                    heap.add(new ScoredChunkId(view.getId(), score));
                } else if (score > heap.peek().score()) {
                    heap.poll();
                    heap.add(new ScoredChunkId(view.getId(), score));
                }
            }

            if (!slice.hasNext()) break;
            page++;
        }

        List<ScoredChunkId> out = new ArrayList<>(heap);
        out.sort(Comparator.comparingDouble(ScoredChunkId::score).reversed());
        if (out.isEmpty()) return List.of();

        List<Long> ids = out.stream().map(ScoredChunkId::chunkId).toList();
        List<KnowledgeChunk> chunks = chunkRepo.findWithDocumentByIdIn(ids);

        Map<Long, KnowledgeChunk> chunkById = chunks.stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgeChunk::getId, c -> c));

        return out.stream()
                .map(sc -> new ScoredChunk(chunkById.get(sc.chunkId()), sc.score()))
                .filter(sc -> sc.chunk() != null)
                .toList();
    }

    public List<SourceDto> toSourceDtos(List<ScoredChunk> scored) {
        return scored.stream().map(sc -> {
            KnowledgeChunk c = sc.chunk();
            KnowledgeDocument d = c.getDocument();
            String text = c.getText();
            String snippet = text.length() > 220 ? text.substring(0, 220) + "…" : text;
            return new SourceDto(c.getId(), d.getId(), d.getTitle(), sc.score(), snippet);
        }).toList();
    }

    private record ScoredChunkId(Long chunkId, double score) {}
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
