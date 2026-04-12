package com.example.apiasistente.rag.service;

import com.example.apiasistente.rag.entity.KnowledgeDocument;
import com.example.apiasistente.rag.dto.RagOpsStatusDto;
import com.example.apiasistente.rag.repository.KnowledgeChunkRepository;
import com.example.apiasistente.rag.repository.KnowledgeDocumentRepository;
import com.example.apiasistente.rag.repository.KnowledgeVectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagOpsServiceTest {

    @Mock
    private KnowledgeDocumentRepository docRepo;

    @Mock
    private KnowledgeChunkRepository chunkRepo;

    @Mock
    private KnowledgeVectorRepository vectorRepo;

    @Mock
    private ObjectProvider<RagVectorIndexService> vectorIndexProvider;

    @Mock
    private ObjectProvider<RagMaintenanceService> maintenanceServiceProvider;

    @Mock
    private RagVectorIndexService vectorIndexService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private RagOpsService service;

    @BeforeEach
    void setUp() {
        service = new RagOpsService(
                docRepo,
                chunkRepo,
                vectorRepo,
                jdbcTemplate,
                vectorIndexProvider,
                maintenanceServiceProvider,
                10,
                700,
                120,
                5,
                12,
                0.45,
                80
        );

        when(vectorIndexProvider.getIfAvailable()).thenReturn(vectorIndexService);
        when(docRepo.countByActiveTrue()).thenReturn(11L);
        when(chunkRepo.countActive()).thenReturn(42L);
        when(vectorRepo.countActive()).thenReturn(40L);
        when(docRepo.sumMetadataLength()).thenReturn(100L);
        when(chunkRepo.sumTextLength()).thenReturn(200L);
        when(vectorRepo.sumEmbeddingLength()).thenReturn(300L);
        when(docRepo.findLastActiveUpdateAt()).thenReturn(Instant.parse("2026-03-03T12:00:00Z"));
        when(vectorIndexService.estimateIndexBytes()).thenReturn(400L);
        when(vectorIndexService.indexLocation()).thenReturn("C:\\data\\rag-hnsw");
    }

    @Test
    void statusAggregatesCorpusCountersAndRecentEvents() {
        service.recordIngest("global", "Doc A", 7L, 3, "scraper", true, "https://acme.test/a");
        service.recordRetrieval(
                "como funciona",
                List.of("global", "ana"),
                8,
                4,
                3,
                2,
                12.3,
                18.9,
                List.of("Doc A"),
                true
        );
        service.recordChunkPrune("global", 2);
        service.recordDocumentDelete("global", "Doc A", 7L, 3, "maintenance");
        service.recordIndexWrite(3);
        service.recordIndexDelete(2);
        service.recordIndexRebuild("manual-ui", 40);

        RagOpsStatusDto status = service.status();

        assertEquals("documents + chunks append-only + vectors persistidos + indice HNSW", status.architecture());
        assertEquals("C:\\data\\rag-hnsw", status.indexLocation());
        assertEquals(11L, status.activeDocuments());
        assertEquals(42L, status.activeChunks());
        assertEquals(40L, status.activeVectors());
        assertEquals(1000L, status.totalBytes());
        assertEquals(1L, status.ingestOperations());
        assertEquals(1L, status.retrievalOperations());
        assertEquals(1L, status.deletedDocuments());
        assertEquals(2L, status.prunedChunks());
        assertEquals(3L, status.indexWrites());
        assertEquals(2L, status.indexDeletes());
        assertEquals(1L, status.indexRebuilds());
        assertTrue(status.lastRetrievalSummary().contains("owners=global, ana"));
        assertFalse(status.recentEvents().isEmpty());
    }

    @Test
    void rebuildIndexDelegatesToVectorServiceAndReturnsSnapshot() {
        RagOpsStatusDto status = service.rebuildIndex("manual-ui");

        verify(vectorIndexService).rebuildFromDatabase("manual-ui");
        assertEquals(11L, status.activeDocuments());
    }

    @Test
    void clearRecentEventsDropsUiBufferButPreservesCounters() {
        service.recordFailure("rag-index", "fallo controlado", null);
        assertFalse(service.status().recentEvents().isEmpty());

        RagOpsStatusDto status = service.clearRecentEvents();

        assertTrue(status.recentEvents().isEmpty());
        assertEquals(1L, status.failures());
    }

    @Test
    void purgeOldestDocumentsDeletesDocumentsChunksAndVectors() {
        KnowledgeDocument first = doc(10L, "Doc viejo 1");
        KnowledgeDocument second = doc(11L, "Doc viejo 2");
        when(docRepo.findOldestActive(any(Pageable.class))).thenReturn(List.of(first, second));
        when(chunkRepo.findIdsByDocumentId(10L)).thenReturn(List.of(100L, 101L));
        when(chunkRepo.findIdsByDocumentId(11L)).thenReturn(List.of(110L));

        RagOpsStatusDto status = service.purgeOldestDocuments(2);

        verify(vectorRepo).deleteByChunkIdIn(List.of(100L, 101L));
        verify(vectorRepo).deleteByChunkIdIn(List.of(110L));
        verify(vectorIndexService).deleteChunkIds(List.of(100L, 101L));
        verify(vectorIndexService).deleteChunkIds(List.of(110L));
        verify(chunkRepo).deleteByDocument_Id(10L);
        verify(chunkRepo).deleteByDocument_Id(11L);
        verify(docRepo).delete(first);
        verify(docRepo).delete(second);
        assertEquals(2L, status.deletedDocuments());
        assertTrue(status.lastDeleteSummary().contains("2 documentos antiguos"));
    }

    private KnowledgeDocument doc(Long id, String title) {
        KnowledgeDocument doc = new KnowledgeDocument();
        ReflectionTestUtils.setField(doc, "id", id);
        doc.setTitle(title);
        doc.setOwner("global");
        return doc;
    }
}
