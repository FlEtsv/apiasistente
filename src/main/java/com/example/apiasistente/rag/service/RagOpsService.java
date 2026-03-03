package com.example.apiasistente.rag.service;

import com.example.apiasistente.rag.dto.RagOpsEventDto;
import com.example.apiasistente.rag.dto.RagOpsStatusDto;
import com.example.apiasistente.rag.repository.KnowledgeChunkRepository;
import com.example.apiasistente.rag.repository.KnowledgeDocumentRepository;
import com.example.apiasistente.rag.repository.KnowledgeVectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Consola operativa del RAG.
 *
 * Responsabilidad:
 * - Centralizar eventos funcionales de ingesta, retrieval, borrado e indice.
 * - Exponer un snapshot legible para la web sin obligar a leer logs crudos.
 * - Mantener trazas recientes en memoria para depuracion de cambios manuales.
 */
@Service
public class RagOpsService {

    private static final Logger log = LoggerFactory.getLogger(RagOpsService.class);
    private static final String ARCHITECTURE = "documents + chunks append-only + vectors persistidos + indice HNSW";

    private final KnowledgeDocumentRepository docRepo;
    private final KnowledgeChunkRepository chunkRepo;
    private final KnowledgeVectorRepository vectorRepo;
    private final ObjectProvider<RagVectorIndexService> vectorIndexServiceProvider;
    private final int topK;
    private final int chunkSize;
    private final int chunkOverlap;
    private final int contextMaxChunks;
    private final int rerankCandidates;
    private final double evidenceThreshold;
    private final int maxEvents;

    private final Deque<RagOpsEventDto> recentEvents = new ArrayDeque<>();
    private final AtomicLong ingestOperations = new AtomicLong();
    private final AtomicLong retrievalOperations = new AtomicLong();
    private final AtomicLong deletedDocuments = new AtomicLong();
    private final AtomicLong prunedChunks = new AtomicLong();
    private final AtomicLong indexWrites = new AtomicLong();
    private final AtomicLong indexDeletes = new AtomicLong();
    private final AtomicLong indexRebuilds = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    private volatile Instant lastIngestAt;
    private volatile Instant lastRetrievalAt;
    private volatile Instant lastDeleteAt;
    private volatile Instant lastIndexAt;
    private volatile String lastIngestSummary = "Sin ingestas registradas.";
    private volatile String lastRetrievalSummary = "Sin retrievals registrados.";
    private volatile String lastDeleteSummary = "Sin borrados registrados.";
    private volatile String lastIndexSummary = "Sin actividad de indice registrada.";

    public RagOpsService(KnowledgeDocumentRepository docRepo,
                         KnowledgeChunkRepository chunkRepo,
                         KnowledgeVectorRepository vectorRepo,
                         ObjectProvider<RagVectorIndexService> vectorIndexServiceProvider,
                         @Value("${rag.top-k:10}") int topK,
                         @Value("${rag.chunk.size:900}") int chunkSize,
                         @Value("${rag.chunk.overlap:150}") int chunkOverlap,
                         @Value("${rag.context.max-chunks:5}") int contextMaxChunks,
                         @Value("${rag.rerank.candidates:12}") int rerankCandidates,
                         @Value("${rag.score.evidence-threshold:0.45}") double evidenceThreshold,
                         @Value("${rag.ops.max-events:120}") int maxEvents) {
        this.docRepo = docRepo;
        this.chunkRepo = chunkRepo;
        this.vectorRepo = vectorRepo;
        this.vectorIndexServiceProvider = vectorIndexServiceProvider;
        this.topK = topK;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.contextMaxChunks = contextMaxChunks;
        this.rerankCandidates = rerankCandidates;
        this.evidenceThreshold = evidenceThreshold;
        this.maxEvents = Math.max(10, maxEvents);
    }

    /**
     * Registra cualquier ingesta aceptada por el core, incluyendo reingestas con fingerprint igual.
     */
    public void recordIngest(String owner,
                             String title,
                             Long documentId,
                             int chunkCount,
                             String source,
                             boolean createdNewVersion,
                             String referenceUrl) {
        ingestOperations.incrementAndGet();
        lastIngestAt = Instant.now();
        lastIngestSummary = createdNewVersion
                ? "Doc " + safeId(documentId) + " en owner '" + safe(owner) + "' con " + chunkCount + " chunks."
                : "Metadata refrescada para '" + safe(title) + "' sin crear nueva version.";
        recordEvent(
                "INFO",
                createdNewVersion ? "INGEST" : "INGEST_REFRESH",
                safe(title),
                "owner=" + safe(owner)
                        + " source=" + safe(source)
                        + " docId=" + safeId(documentId)
                        + " chunks=" + Math.max(0, chunkCount)
                        + (referenceUrl == null || referenceUrl.isBlank() ? "" : " url=" + referenceUrl)
        );
    }

    public void recordRetrieval(String query,
                                List<String> owners,
                                int semanticCandidates,
                                int selectedChunks,
                                int evidenceChunks,
                                int contextChunks,
                                double embeddingTimeMs,
                                double retrievalTimeMs,
                                List<String> sourceDocs,
                                boolean hasEvidence) {
        retrievalOperations.incrementAndGet();
        lastRetrievalAt = Instant.now();
        lastRetrievalSummary = "owners=" + safeOwners(owners)
                + " candidates=" + Math.max(0, semanticCandidates)
                + " context=" + Math.max(0, contextChunks)
                + " evidence=" + (hasEvidence ? "si" : "no")
                + " retrievalMs=" + formatMillis(retrievalTimeMs);
        recordEvent(
                hasEvidence ? "INFO" : "WARN",
                hasEvidence ? "RETRIEVE" : "RETRIEVE_EMPTY",
                summarizeQuery(query),
                "owners=" + safeOwners(owners)
                        + " selected=" + Math.max(0, selectedChunks)
                        + " evidence=" + Math.max(0, evidenceChunks)
                        + " context=" + Math.max(0, contextChunks)
                        + " embedMs=" + formatMillis(embeddingTimeMs)
                        + " retrievalMs=" + formatMillis(retrievalTimeMs)
                        + " docs=" + safeOwners(sourceDocs)
        );
    }

    public void recordDocumentDelete(String owner, String title, Long documentId, int chunkCount, String origin) {
        deletedDocuments.incrementAndGet();
        lastDeleteAt = Instant.now();
        lastDeleteSummary = "Doc " + safeId(documentId) + " eliminado desde " + safe(origin) + ".";
        recordEvent(
                "WARN",
                "DELETE_DOC",
                safe(title),
                "owner=" + safe(owner)
                        + " docId=" + safeId(documentId)
                        + " chunks=" + Math.max(0, chunkCount)
                        + " origin=" + safe(origin)
        );
    }

    public void recordChunkPrune(String owner, int chunkCount) {
        prunedChunks.addAndGet(Math.max(0, chunkCount));
        lastDeleteAt = Instant.now();
        lastDeleteSummary = "Poda de " + Math.max(0, chunkCount) + " chunks en owner '" + safe(owner) + "'.";
        recordEvent(
                "INFO",
                "PRUNE_CHUNKS",
                "Poda selectiva",
                "owner=" + safe(owner) + " chunks=" + Math.max(0, chunkCount)
        );
    }

    public void recordIndexWrite(int vectorCount) {
        indexWrites.addAndGet(Math.max(0, vectorCount));
        lastIndexAt = Instant.now();
        lastIndexSummary = "Indexados " + Math.max(0, vectorCount) + " vectores.";
        recordEvent(
                "INFO",
                "INDEX_WRITE",
                "Indice HNSW actualizado",
                "vectores=" + Math.max(0, vectorCount)
        );
    }

    public void recordIndexDelete(int vectorCount) {
        indexDeletes.addAndGet(Math.max(0, vectorCount));
        lastIndexAt = Instant.now();
        lastIndexSummary = "Eliminados " + Math.max(0, vectorCount) + " vectores del indice.";
        recordEvent(
                "INFO",
                "INDEX_DELETE",
                "Indice HNSW podado",
                "vectores=" + Math.max(0, vectorCount)
        );
    }

    public void recordIndexRebuild(String trigger, int vectorCount) {
        indexRebuilds.incrementAndGet();
        lastIndexAt = Instant.now();
        lastIndexSummary = "Reconstruccion completa del indice desde vectors: " + Math.max(0, vectorCount) + " vectores.";
        recordEvent(
                "WARN",
                "INDEX_REBUILD",
                "Reindexado HNSW",
                "trigger=" + safe(trigger) + " vectores=" + Math.max(0, vectorCount)
        );
    }

    public void recordFailure(String stage, String detail, Throwable error) {
        failures.incrementAndGet();
        String cause = detail == null || detail.isBlank()
                ? safe(error == null ? null : error.getMessage())
                : detail.trim();
        recordEvent(
                "ERROR",
                "FAILURE",
                safe(stage),
                cause
        );
        if (error == null) {
            log.warn("RAG ops failure stage={} detail={}", safe(stage), cause);
        } else {
            log.warn("RAG ops failure stage={} detail={}", safe(stage), cause, error);
        }
    }

    /**
     * Snapshot operativo consumido por la home.
     */
    public RagOpsStatusDto status() {
        RagVectorIndexService indexService = vectorIndexServiceProvider.getIfAvailable();
        long metadataBytes = safeLong(docRepo.sumMetadataLength());
        long chunkTextBytes = safeLong(chunkRepo.sumTextLength());
        long embeddingBytes = safeLong(vectorRepo.sumEmbeddingLength());
        long indexBytes = indexService == null ? 0L : Math.max(0L, indexService.estimateIndexBytes());
        return new RagOpsStatusDto(
                ARCHITECTURE,
                indexService == null ? "-" : indexService.indexLocation(),
                Instant.now(),
                docRepo.findLastActiveUpdateAt(),
                docRepo.countByActiveTrue(),
                chunkRepo.countActive(),
                vectorRepo.countActive(),
                metadataBytes,
                chunkTextBytes,
                embeddingBytes,
                indexBytes,
                metadataBytes + chunkTextBytes + embeddingBytes + indexBytes,
                topK,
                chunkSize,
                chunkOverlap,
                contextMaxChunks,
                rerankCandidates,
                evidenceThreshold,
                ingestOperations.get(),
                retrievalOperations.get(),
                deletedDocuments.get(),
                prunedChunks.get(),
                indexWrites.get(),
                indexDeletes.get(),
                indexRebuilds.get(),
                failures.get(),
                lastIngestAt,
                lastIngestSummary,
                lastRetrievalAt,
                lastRetrievalSummary,
                lastDeleteAt,
                lastDeleteSummary,
                lastIndexAt,
                lastIndexSummary,
                recentEvents(20)
        );
    }

    public RagOpsStatusDto rebuildIndex(String trigger) {
        RagVectorIndexService indexService = vectorIndexServiceProvider.getIfAvailable();
        if (indexService == null) {
            recordFailure("index", "Servicio de indice no disponible.", null);
            return status();
        }
        indexService.rebuildFromDatabase(trigger);
        return status();
    }

    public RagOpsStatusDto clearRecentEvents() {
        synchronized (recentEvents) {
            recentEvents.clear();
        }
        return status();
    }

    /**
     * Purga documentos activos antiguos con todos sus chunks y vectores.
     *
     * Uso:
     * - Operacion manual de emergencia cuando el almacenamiento esta saturado.
     * - Pensada para actuar sobre los documentos mas viejos sin tocar el resto del corpus.
     */
    @Transactional
    public RagOpsStatusDto purgeOldestDocuments(int requestedCount) {
        int safeCount = Math.max(1, Math.min(500, requestedCount));
        List<com.example.apiasistente.rag.entity.KnowledgeDocument> docs = docRepo.findOldestActive(PageRequest.of(0, safeCount));
        if (docs.isEmpty()) {
            lastDeleteAt = Instant.now();
            lastDeleteSummary = "No habia documentos activos para purgar.";
            recordEvent("INFO", "PURGE_OLDEST_EMPTY", "Purgado sin cambios", "No habia documentos activos.");
            return status();
        }

        RagVectorIndexService indexService = vectorIndexServiceProvider.getIfAvailable();
        int deletedDocCount = 0;
        int deletedChunkCount = 0;
        List<String> sampleTitles = new ArrayList<>();

        for (var doc : docs) {
            List<Long> chunkIds = chunkRepo.findIdsByDocumentId(doc.getId());
            if (!chunkIds.isEmpty()) {
                vectorRepo.deleteByChunkIdIn(chunkIds);
                if (indexService != null) {
                    indexService.deleteChunkIds(chunkIds);
                }
                chunkRepo.deleteByDocument_Id(doc.getId());
                deletedChunkCount += chunkIds.size();
            }
            docRepo.delete(doc);
            deletedDocCount++;
            deletedDocuments.incrementAndGet();
            if (sampleTitles.size() < 4) {
                sampleTitles.add(safe(doc.getTitle()));
            }
        }

        lastDeleteAt = Instant.now();
        lastDeleteSummary = "Purgados " + deletedDocCount + " documentos antiguos y " + deletedChunkCount + " chunks.";
        recordEvent(
                "WARN",
                "PURGE_OLDEST",
                "Purgado manual",
                "docs=" + deletedDocCount
                        + " chunks=" + deletedChunkCount
                        + " muestra=" + safeOwners(sampleTitles)
        );
        return status();
    }

    private List<RagOpsEventDto> recentEvents(int limit) {
        int safeLimit = Math.max(1, limit);
        List<RagOpsEventDto> out = new ArrayList<>(safeLimit);
        synchronized (recentEvents) {
            for (RagOpsEventDto event : recentEvents) {
                out.add(event);
                if (out.size() >= safeLimit) {
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    private void recordEvent(String level, String type, String summary, String detail) {
        RagOpsEventDto event = new RagOpsEventDto(
                Instant.now(),
                safe(level).toUpperCase(Locale.ROOT),
                safe(type),
                safe(summary),
                safe(detail)
        );
        synchronized (recentEvents) {
            recentEvents.addFirst(event);
            while (recentEvents.size() > maxEvents) {
                recentEvents.removeLast();
            }
        }
    }

    private static long safeLong(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.trim();
    }

    private static String safeId(Long value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private static String summarizeQuery(String value) {
        String safeValue = safe(value);
        if (safeValue.length() <= 72) {
            return safeValue;
        }
        return safeValue.substring(0, 72).trim() + "...";
    }

    private static String safeOwners(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "-";
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .limit(4)
                .map(String::trim)
                .reduce((left, right) -> left + ", " + right)
                .orElse("-");
    }

    private static String formatMillis(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            return "0.0";
        }
        return String.format(Locale.US, "%.1f", value);
    }
}
