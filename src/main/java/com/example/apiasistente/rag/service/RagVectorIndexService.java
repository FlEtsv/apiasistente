package com.example.apiasistente.rag.service;

import com.example.apiasistente.rag.repository.KnowledgeVectorRepository;
import com.example.apiasistente.rag.util.VectorMath;
import com.example.apiasistente.shared.ai.OllamaClient;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Capa de indice vectorial HNSW.
 *
 * Decisiones de diseño:
 * - El indice se mantiene fuera de MySQL para que retrieval no tenga que escanear embeddings fila a fila.
 * - El respaldo durable sigue en la tabla `vectors`, que permite reconstruir el indice al arrancar.
 * - El mapping minimo del indice es `chunk_id -> owner -> embedding`.
 */
@Service
public class RagVectorIndexService {

    private static final Logger log = LoggerFactory.getLogger(RagVectorIndexService.class);
    private static final String CHUNK_ID_FIELD = "chunk_id";
    private static final String CHUNK_ID_STORED_FIELD = "chunk_id_stored";
    private static final String OWNER_FIELD = "owner";
    private static final String CREATED_AT_FIELD = "created_at";
    private static final String VECTOR_FIELD = "embedding";
    private static final int REBUILD_PAGE_SIZE = 500;

    private final KnowledgeVectorRepository vectorRepo;
    private final OllamaClient ollamaClient;
    private final Directory directory;
    private final IndexWriter writer;
    private final Path indexPath;
    private final boolean rebuildOnStartup;
    private final ObjectProvider<RagOpsService> ragOpsServiceProvider;
    // ReadWriteLock: multiples busquedas concurrentes (readLock), escrituras exclusivas (writeLock).
    private final ReadWriteLock indexLock = new ReentrantReadWriteLock();

    public RagVectorIndexService(KnowledgeVectorRepository vectorRepo,
                                 OllamaClient ollamaClient,
                                 ObjectProvider<RagOpsService> ragOpsServiceProvider,
                                 @Value("${rag.vector.index-dir:data/rag-hnsw}") String indexDir,
                                 @Value("${rag.vector.rebuild-on-startup:true}") boolean rebuildOnStartup) throws IOException {
        this.vectorRepo = vectorRepo;
        this.ollamaClient = ollamaClient;
        this.ragOpsServiceProvider = ragOpsServiceProvider;
        this.rebuildOnStartup = rebuildOnStartup;

        Path dir = Path.of(indexDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        this.indexPath = dir;
        this.directory = FSDirectory.open(dir);
        this.writer = new IndexWriter(directory, new IndexWriterConfig(new KeywordAnalyzer()));
    }

    /**
     * El arranque lanza el rebuild en un hilo daemon para no bloquear el arranque ni las primeras
     * peticiones. El indice queda vacio/parcial hasta que el rebuild termina: las busquedas devuelven
     * resultados vacios (noRag) en ese intervalo, lo cual es preferible a bloquear indefinidamente.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildOnStartup() {
        if (!rebuildOnStartup) {
            return;
        }
        Executors.newVirtualThreadPerTaskExecutor().execute(() -> rebuildFromDatabase("startup"));
    }

    public void rebuildFromDatabase() {
        rebuildFromDatabase("system");
    }

    /**
     * Recorre `vectors` como fuente durable y recompone el HNSW completo.
     * Se expone con trigger explicito para poder auditar si fue un arranque o una accion manual.
     *
     * Diseño de locking: el writeLock NO se sostiene durante el fetch de paginas de BD porque
     * con corpus grandes (>100k vectores) el rebuild tarda minutos y bloquea todas las busquedas.
     * En cambio: se borra todo en un unico lock, luego se indexa batch a batch (lock por batch),
     * lo que permite que las busquedas vean datos parciales/vacios durante el rebuild en lugar de
     * bloquearse indefinidamente.
     */
    public void rebuildFromDatabase(String trigger) {
        // Paso 1: borrar el indice viejo bajo lock (operacion rapida).
        indexLock.writeLock().lock();
        try {
            writer.deleteAll();
            writer.commit();
        } catch (Exception e) {
            log.warn("No se pudo limpiar el indice HNSW del RAG antes de rebuild", e);
            ragOps().ifPresent(ops -> ops.recordFailure("rag-index-rebuild", "No se pudo limpiar el indice HNSW.", e));
            return;
        } finally {
            indexLock.writeLock().unlock();
        }

        // Paso 2: paginar BD y escribir en Lucene batch a batch.
        // El fetch de BD ocurre SIN lock para no bloquear busquedas durante el rebuild.
        int page = 0;
        int indexedVectors = 0;
        try {
            while (true) {
                var slice = vectorRepo.findActiveIndexPage(PageRequest.of(page, REBUILD_PAGE_SIZE));
                if (slice.isEmpty()) {
                    break;
                }

                List<IndexedVectorRecord> batch = new ArrayList<>(slice.getNumberOfElements());
                for (var view : slice.getContent()) {
                    double[] normalized = VectorMath.normalize(ollamaClient.fromJson(view.getEmbeddingJson()));
                    if (normalized.length == 0) {
                        continue;
                    }
                    batch.add(new IndexedVectorRecord(
                            view.getChunkId(),
                            view.getOwner(),
                            toFloatArray(normalized),
                            Instant.now()
                    ));
                }

                if (!batch.isEmpty()) {
                    indexLock.writeLock().lock();
                    try {
                        indexBatchInternal(batch);
                        writer.commit();
                    } finally {
                        indexLock.writeLock().unlock();
                    }
                }

                indexedVectors += batch.size();
                if (!slice.hasNext()) {
                    break;
                }
                page++;
            }

            log.info("RAG HNSW rebuild completado pages={} vectors={}", page + 1, indexedVectors);
            int finalIndexedVectors = indexedVectors;
            ragOps().ifPresent(ops -> ops.recordIndexRebuild(trigger, finalIndexedVectors));
        } catch (Exception e) {
            log.warn("No se pudo reconstruir el indice HNSW del RAG", e);
            ragOps().ifPresent(ops -> ops.recordFailure("rag-index-rebuild", "No se pudo reconstruir el indice HNSW.", e));
        }
    }

    /**
     * Inserta o actualiza vectores de chunks activos.
     * Siempre se borra primero por `chunk_id` para mantener idempotencia simple.
     */
    public void indexBatch(List<IndexedVectorRecord> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            return;
        }
        indexLock.writeLock().lock();
        try {
            indexBatchInternal(vectors);
            writer.commit();
            ragOps().ifPresent(ops -> ops.recordIndexWrite(vectors.size()));
        } catch (IOException e) {
            ragOps().ifPresent(ops -> ops.recordFailure("rag-index-write", "No se pudieron indexar vectores RAG.", e));
            throw new IllegalStateException("No se pudieron indexar vectores RAG.", e);
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    public void deleteChunkIds(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        indexLock.writeLock().lock();
        try {
            for (Long chunkId : chunkIds) {
                if (chunkId == null) {
                    continue;
                }
                writer.deleteDocuments(new Term(CHUNK_ID_FIELD, String.valueOf(chunkId)));
            }
            writer.commit();
            ragOps().ifPresent(ops -> ops.recordIndexDelete(chunkIds.size()));
        } catch (IOException e) {
            ragOps().ifPresent(ops -> ops.recordFailure("rag-index-delete", "No se pudieron borrar vectores del indice HNSW.", e));
            throw new IllegalStateException("No se pudieron borrar vectores del indice HNSW.", e);
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    /**
     * Busca candidatos semanticos en HNSW y filtra owners al final.
     * Usa readLock para permitir busquedas concurrentes sin bloquear entre si.
     */
    public List<SearchHit> search(List<String> owners, double[] queryVector, int limit) {
        if (queryVector == null || queryVector.length == 0 || limit <= 0) {
            return List.of();
        }

        indexLock.readLock().lock();
        try {
            if (!DirectoryReader.indexExists(directory)) {
                return List.of();
            }
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                if (reader.numDocs() <= 0) {
                    return List.of();
                }

                Set<String> ownerFilter = new LinkedHashSet<>();
                if (owners != null) {
                    ownerFilter.addAll(owners);
                }
                int fetchK = Math.max(limit, limit * Math.max(2, ownerFilter.size() * 3));
                IndexSearcher searcher = new IndexSearcher(reader);
                var query = new KnnFloatVectorQuery(VECTOR_FIELD, toFloatArray(queryVector), fetchK);
                ScoreDoc[] docs = searcher.search(query, fetchK).scoreDocs;

                List<SearchHit> hits = new ArrayList<>(Math.min(limit, docs.length));
                for (ScoreDoc scoreDoc : docs) {
                    Document doc = searcher.doc(scoreDoc.doc);
                    String owner = doc.get(OWNER_FIELD);
                    if (!ownerFilter.isEmpty() && !ownerFilter.contains(owner)) {
                        continue;
                    }
                    long chunkId = Long.parseLong(doc.get(CHUNK_ID_STORED_FIELD));
                    hits.add(new SearchHit(chunkId, owner, scoreDoc.score));
                    if (hits.size() >= limit) {
                        break;
                    }
                }
                return hits;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Fallo buscando en el indice HNSW del RAG.", e);
        } finally {
            indexLock.readLock().unlock();
        }
    }

    public long estimateIndexBytes() {
        try {
            if (!(directory instanceof FSDirectory fsDirectory)) {
                return 0L;
            }
            try (var files = Files.walk(fsDirectory.getDirectory())) {
                return files
                        .filter(Files::isRegularFile)
                        .mapToLong(path -> {
                            try {
                                return Files.size(path);
                            } catch (IOException ignored) {
                                return 0L;
                            }
                        })
                        .sum();
            }
        } catch (IOException e) {
            log.debug("No se pudo estimar tamano del indice HNSW", e);
            return 0L;
        }
    }

    /**
     * Ruta del indice para inspeccion operativa desde la home y soporte manual.
     */
    public String indexLocation() {
        return indexPath.toString();
    }

    @PreDestroy
    public void shutdown() {
        indexLock.writeLock().lock();
        try {
            writer.close();
            directory.close();
        } catch (IOException e) {
            log.debug("No se pudo cerrar el indice HNSW del RAG", e);
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    private void indexBatchInternal(List<IndexedVectorRecord> vectors) throws IOException {
        for (IndexedVectorRecord vector : vectors) {
            if (vector == null || vector.chunkId() == null || vector.owner() == null || vector.owner().isBlank()) {
                continue;
            }
            if (vector.embedding() == null || vector.embedding().length == 0) {
                continue;
            }

            Document doc = new Document();
            doc.add(new StringField(CHUNK_ID_FIELD, String.valueOf(vector.chunkId()), org.apache.lucene.document.Field.Store.NO));
            doc.add(new StoredField(CHUNK_ID_STORED_FIELD, vector.chunkId()));
            doc.add(new StringField(OWNER_FIELD, vector.owner(), org.apache.lucene.document.Field.Store.YES));
            doc.add(new StoredField(CREATED_AT_FIELD, vector.createdAt().toEpochMilli()));
            // Los embeddings ya vienen normalizados; DOT_PRODUCT encaja bien con HNSW en este caso.
            doc.add(new KnnFloatVectorField(VECTOR_FIELD, vector.embedding(), VectorSimilarityFunction.DOT_PRODUCT));

            writer.updateDocument(new Term(CHUNK_ID_FIELD, String.valueOf(vector.chunkId())), doc);
        }
    }

    private java.util.Optional<RagOpsService> ragOps() {
        return java.util.Optional.ofNullable(ragOpsServiceProvider.getIfAvailable());
    }

    private static float[] toFloatArray(double[] vector) {
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = (float) vector[i];
        }
        return out;
    }

    public record SearchHit(long chunkId, String owner, float score) {
    }

    public record IndexedVectorRecord(Long chunkId, String owner, float[] embedding, Instant createdAt) {
    }
}
