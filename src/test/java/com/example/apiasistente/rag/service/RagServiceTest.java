package com.example.apiasistente.rag.service;

import com.example.apiasistente.rag.entity.KnowledgeChunk;
import com.example.apiasistente.rag.entity.KnowledgeDocument;
import com.example.apiasistente.rag.repository.KnowledgeChunkRepository;
import com.example.apiasistente.rag.repository.KnowledgeDocumentRepository;
import com.example.apiasistente.rag.repository.KnowledgeVectorRepository;
import com.example.apiasistente.shared.ai.OllamaClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    @Mock
    private KnowledgeDocumentRepository docRepo;

    @Mock
    private KnowledgeChunkRepository chunkRepo;

    @Mock
    private KnowledgeVectorRepository vectorRepo;

    @Mock
    private RagVectorIndexService vectorIndexService;

    @Mock
    private RagOpsService ragOpsService;

    @Mock
    private OllamaClient ollama;

    private RagService service;

    @BeforeEach
    void setUp() {
        service = new RagService(
                docRepo,
                chunkRepo,
                vectorRepo,
                vectorIndexService,
                ragOpsService,
                ollama
        );
    }

    @Test
    void deleteDocumentByIdRemovesVectorsAndChunksButKeepsChatSnapshots() {
        KnowledgeDocument doc = new KnowledgeDocument();
        ReflectionTestUtils.setField(doc, "id", 30L);
        doc.setOwner("global");
        doc.setTitle("Doc roto");

        when(docRepo.findById(30L)).thenReturn(Optional.of(doc));
        when(chunkRepo.findIdsByDocumentId(30L)).thenReturn(List.of(301L, 302L));

        assertTrue(service.deleteDocumentById(30L));

        verify(vectorRepo).deleteByChunkIdIn(List.of(301L, 302L));
        verify(vectorIndexService).deleteChunkIds(List.of(301L, 302L));
        verify(chunkRepo).deleteByDocument_Id(30L);
        verify(docRepo).delete(doc);
        verify(ragOpsService).recordDocumentDelete("global", "Doc roto", 30L, 2, "rag-service");
    }

    @Test
    void deleteChunkIdsRemovesSelectedChunksWithoutTouchingChatSnapshots() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setOwner("global");

        KnowledgeChunk first = new KnowledgeChunk();
        ReflectionTestUtils.setField(first, "id", 501L);
        first.setDocument(doc);

        KnowledgeChunk second = new KnowledgeChunk();
        ReflectionTestUtils.setField(second, "id", 502L);
        second.setDocument(doc);

        when(chunkRepo.findWithDocumentByIdIn(List.of(501L, 502L, 999L))).thenReturn(List.of(first, second));

        int deleted = service.deleteChunkIds("global", List.of(501L, 502L, 999L));

        assertEquals(2, deleted);
        verify(vectorRepo).deleteByChunkIdIn(List.of(501L, 502L));
        verify(vectorIndexService).deleteChunkIds(List.of(501L, 502L));
        verify(chunkRepo).deleteAllByIdInBatch(List.of(501L, 502L));
        verify(ragOpsService).recordChunkPrune("global", 2);
    }
}
