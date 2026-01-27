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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RagService {

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

    @Transactional
    public KnowledgeDocument upsertDocument(String title, String content) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTitle(title);
        doc.setContent(content);
        doc = docRepo.save(doc);

        List<String> chunks = TextChunker.chunk(content, chunkSize, overlap);
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
        String cleanTitle = (title == null) ? "" : title.trim();
        if (cleanTitle.isBlank()) {
            cleanTitle = "Memoria/" + username + "/" + Instant.now();
        }
        return upsertDocument(cleanTitle, content);
    }

    /**
     * Retrieval simple (pero correcto): recorre chunks en páginas, calcula cosine y devuelve Top-K.
     * Para “escala grande”, lo reemplazas por un vector DB; pero esto cumple tu requisito de guardarlo en MySQL.
     */
    public List<ScoredChunk> retrieveTopK(String query) {
        double[] q = ollama.embedOne(query);
        if (q.length == 0) return List.of();

        PriorityQueue<ScoredChunkId> heap = new PriorityQueue<>(Comparator.comparingDouble(ScoredChunkId::score)); // min-heap
        int page = 0;
        int size = 500;

        while (true) {
            var slice = chunkRepo.findEmbeddingPage(PageRequest.of(page, size));
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
}
