package com.example.apiasistente.rag.service;

import com.example.apiasistente.chat.repository.ChatMessageSourceRepository;
import com.example.apiasistente.monitoring.dto.MonitoringAlertStateDto;
import com.example.apiasistente.monitoring.service.MonitoringAlertService;
import com.example.apiasistente.rag.config.RagMaintenanceProperties;
import com.example.apiasistente.rag.dto.RagMaintenanceCaseDecisionRequest;
import com.example.apiasistente.rag.entity.KnowledgeChunk;
import com.example.apiasistente.rag.entity.KnowledgeDocument;
import com.example.apiasistente.rag.entity.RagMaintenanceAction;
import com.example.apiasistente.rag.entity.RagMaintenanceCase;
import com.example.apiasistente.rag.entity.RagMaintenanceCaseStatus;
import com.example.apiasistente.rag.entity.RagMaintenanceIssueType;
import com.example.apiasistente.rag.entity.RagMaintenanceSeverity;
import com.example.apiasistente.rag.repository.KnowledgeChunkRepository;
import com.example.apiasistente.rag.repository.KnowledgeDocumentRepository;
import com.example.apiasistente.rag.repository.KnowledgeVectorRepository;
import com.example.apiasistente.rag.repository.RagMaintenanceCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagMaintenanceServiceTest {

    @Mock
    private KnowledgeDocumentRepository docRepo;

    @Mock
    private KnowledgeChunkRepository chunkRepo;

    @Mock
    private RagMaintenanceCaseRepository caseRepo;

    @Mock
    private KnowledgeVectorRepository vectorRepo;

    @Mock
    private ChatMessageSourceRepository sourceRepo;

    @Mock
    private RagMaintenanceAdvisorService advisorService;

    @Mock
    private MonitoringAlertService monitoringAlertService;

    @Mock
    private RagService ragService;

    @Mock
    private RagVectorIndexService vectorIndexService;

    private RagMaintenanceService service;

    @BeforeEach
    void setUp() {
        RagMaintenanceProperties properties = new RagMaintenanceProperties();
        properties.setDryRun(false);
        properties.setEnabled(true);
        properties.setIntervalMs(60000);
        properties.setPageSize(10);
        properties.setWarningReviewHours(48);
        properties.setAiAutoApplyHours(24);
        properties.setAdminBacklogThreshold(10);
        properties.setAdminBacklogAiMaxPerPass(3);
        properties.setAiRequireHealthyMonitoring(true);
        properties.setUnusedDaysThreshold(30);

        service = new RagMaintenanceService(
                properties,
                docRepo,
                chunkRepo,
                vectorRepo,
                caseRepo,
                sourceRepo,
                advisorService,
                monitoringAlertService,
                vectorIndexService,
                ragService,
                700,
                120
        );

        lenient().when(caseRepo.save(any(RagMaintenanceCase.class))).thenAnswer(invocation -> {
            RagMaintenanceCase ragCase = invocation.getArgument(0);
            if (ragCase.getId() == null) {
                ReflectionTestUtils.setField(ragCase, "id", 900L);
            }
            return ragCase;
        });
        lenient().when(caseRepo.findFirstByDocumentIdAndStatusInOrderByCreatedAtDesc(anyLong(), anyCollection()))
                .thenReturn(Optional.empty());
        lenient().when(caseRepo.findTop100ByStatusAndAdminDueAtBeforeOrderByAdminDueAtAsc(any(), any()))
                .thenReturn(List.of());
        lenient().when(caseRepo.findTop100ByStatusOrderByAdminDueAtAscCreatedAtAsc(any()))
                .thenReturn(List.of());
        lenient().when(caseRepo.findEligibleForBacklogAcceleration(any(), any(), any(Pageable.class)))
                .thenReturn(List.of());
        lenient().when(caseRepo.findTop100ByStatusAndAutoApplyAtBeforeOrderByAutoApplyAtAsc(any(), any()))
                .thenReturn(List.of());
        lenient().when(caseRepo.countByStatus(any())).thenReturn(0L);
        lenient().when(caseRepo.countEligibleForBacklogAcceleration(any(), any())).thenReturn(0L);
        lenient().when(monitoringAlertService.currentState()).thenReturn(healthyMonitoring());
    }

    @Test
    void duplicateWarningCreatesCaseInsteadOfDeletingImmediately() {
        KnowledgeDocument newest = document(20L, "global", "Doc nuevo", "Contexto tecnico util sobre endpoints y despliegue.");
        KnowledgeDocument olderDuplicate = document(10L, "global", "Doc viejo", "Contexto tecnico util sobre endpoints y despliegue.");

        when(docRepo.findSweepPage(isNull(), any(Pageable.class))).thenReturn(List.of(newest, olderDuplicate));
        when(docRepo.findSweepPage(eq(10L), any(Pageable.class))).thenReturn(List.of());
        when(chunkRepo.findActiveByDocumentIdOrderByChunkIndexAsc(20L))
                .thenReturn(List.of(chunk(201L, newest, 0, "Contexto tecnico util sobre endpoints y despliegue.")));
        when(chunkRepo.findActiveByDocumentIdOrderByChunkIndexAsc(10L))
                .thenReturn(List.of(chunk(101L, olderDuplicate, 0, "Contexto tecnico util sobre endpoints y despliegue.")));
        when(sourceRepo.countBySourceDocumentId(anyLong())).thenReturn(1L);
        when(sourceRepo.findLastUsedAtByDocumentId(anyLong())).thenReturn(null);
        mockCorpusSnapshots(2L, 2L, 120L, 220L, 320L);

        service.runSweepBlockingForTest("MANUAL");

        ArgumentCaptor<RagMaintenanceCase> captor = ArgumentCaptor.forClass(RagMaintenanceCase.class);
        verify(caseRepo, atLeastOnce()).save(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(saved ->
                saved.getIssueType() == RagMaintenanceIssueType.DUPLICATE_DOCUMENT
                        && saved.getSeverity() == RagMaintenanceSeverity.WARNING
        ));
        verify(ragService, never()).deleteDocumentById(10L);
    }

    @Test
    void criticalContentTriggersImmediateAiDelete() {
        KnowledgeDocument doc = document(30L, "global", "Doc roto", "@@@ ### %% ???");

        when(docRepo.findSweepPage(isNull(), any(Pageable.class))).thenReturn(List.of(doc));
        when(docRepo.findSweepPage(eq(30L), any(Pageable.class))).thenReturn(List.of());
        when(chunkRepo.findActiveByDocumentIdOrderByChunkIndexAsc(30L))
                .thenReturn(List.of(chunk(301L, doc, 0, "@@@ ### %% ???")));
        when(sourceRepo.countBySourceDocumentId(30L)).thenReturn(0L);
        when(sourceRepo.findLastUsedAtByDocumentId(30L)).thenReturn(null);
        when(advisorService.advise(any(RagMaintenanceCase.class)))
                .thenReturn(new RagMaintenanceAdvisorService.Advice(
                        com.example.apiasistente.rag.entity.RagMaintenanceAction.DELETE,
                        "Contenido inservible para RAG.",
                        null,
                        "{\"decision\":\"DELETE\"}",
                        "fast-model"
                ));
        mockCorpusSnapshots(1L, 1L, 40L, 40L, 20L);

        service.runSweepBlockingForTest("MANUAL");

        verify(ragService).deleteDocumentById(30L);
        ArgumentCaptor<RagMaintenanceCase> captor = ArgumentCaptor.forClass(RagMaintenanceCase.class);
        verify(caseRepo, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        RagMaintenanceCase last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertNotNull(last.getResolvedAt());
        assertEquals(com.example.apiasistente.rag.entity.RagMaintenanceAction.DELETE, last.getFinalAction());
    }

    @Test
    void manualDeleteDecisionExecutesDocumentRemoval() {
        RagMaintenanceCase ragCase = new RagMaintenanceCase();
        ReflectionTestUtils.setField(ragCase, "id", 77L);
        ragCase.setDocumentId(30L);
        ragCase.setOwner("global");
        ragCase.setDocumentTitle("Doc roto");
        ragCase.setSeverity(RagMaintenanceSeverity.CRITICAL);
        ragCase.setIssueType(RagMaintenanceIssueType.BAD_STRUCTURE);
        ragCase.setStatus(RagMaintenanceCaseStatus.OPEN);
        ragCase.setRecommendedAction(RagMaintenanceAction.DELETE);

        when(caseRepo.findById(77L)).thenReturn(Optional.of(ragCase));

        RagMaintenanceCaseDecisionRequest request = new RagMaintenanceCaseDecisionRequest();
        request.setAction("DELETE");

        var result = service.decideCase(77L, request, "ana");

        verify(ragService).deleteDocumentById(30L);
        assertEquals("EXECUTED", result.status());
        assertEquals("DELETE", result.finalAction());
        assertEquals("ana", result.resolvedBy());
    }

    @Test
    void backlogAboveThresholdTriggersAiReviewWhenMonitoringIsHealthy() {
        RagMaintenanceCase ragCase = warningCase(91L, Instant.now().plusSeconds(3600));
        when(caseRepo.countEligibleForBacklogAcceleration(eq(RagMaintenanceCaseStatus.OPEN), any(Instant.class)))
                .thenReturn(11L);
        when(caseRepo.findEligibleForBacklogAcceleration(eq(RagMaintenanceCaseStatus.OPEN), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(ragCase));
        when(advisorService.advise(ragCase))
                .thenReturn(new RagMaintenanceAdvisorService.Advice(
                        RagMaintenanceAction.DELETE,
                        "Backlog alto, adelantar decision.",
                        null,
                        "{\"decision\":\"DELETE\"}",
                        "fast-model"
                ));

        service.scheduledCaseFollowUp();

        verify(advisorService).advise(ragCase);
        assertEquals(RagMaintenanceCaseStatus.AI_REVIEWED, ragCase.getStatus());
        assertNotNull(ragCase.getAutoApplyAt());
        assertTrue(ragCase.getAuditLog().contains("Revision IA adelantada por backlog admin alto"));
        assertTrue(ragCase.getAuditLog().contains("Decision IA con modelo fast-model: DELETE"));
        assertTrue(ragCase.getAuditLog().contains("Caso pendiente de auto-aplicacion tras decision IA"));
    }

    @Test
    void backlogAboveThresholdSkipsAiReviewWhenMonitoringIsDegraded() {
        RagMaintenanceCase ragCase = warningCase(92L, Instant.now().plusSeconds(3600));
        when(caseRepo.countEligibleForBacklogAcceleration(eq(RagMaintenanceCaseStatus.OPEN), any(Instant.class)))
                .thenReturn(11L);
        when(monitoringAlertService.currentState()).thenReturn(new MonitoringAlertStateDto(
                true, false, false, false, false,
                Instant.now(), null, null, null, null
        ));

        service.scheduledCaseFollowUp();

        verify(advisorService, never()).advise(any(RagMaintenanceCase.class));
        verify(caseRepo, never()).save(ragCase);
        assertEquals(RagMaintenanceCaseStatus.OPEN, ragCase.getStatus());
    }

    @Test
    void overdueCaseAlsoRespectsMonitoringGateBeforeCallingAi() {
        RagMaintenanceCase ragCase = warningCase(93L, Instant.now().minusSeconds(60));
        when(caseRepo.findTop100ByStatusAndAdminDueAtBeforeOrderByAdminDueAtAsc(
                eq(RagMaintenanceCaseStatus.OPEN),
                any(Instant.class)
        )).thenReturn(List.of(ragCase));
        when(monitoringAlertService.currentState()).thenReturn(new MonitoringAlertStateDto(
                false, true, false, false, false,
                null, Instant.now(), null, null, null
        ));

        service.scheduledCaseFollowUp();

        verify(advisorService, never()).advise(any(RagMaintenanceCase.class));
        verify(caseRepo).save(ragCase);
        assertEquals(RagMaintenanceCaseStatus.OPEN, ragCase.getStatus());
        assertTrue(ragCase.getAuditLog().contains("IA aplazada por monitoreo degradado: memoryHigh."));
    }

    private void mockCorpusSnapshots(long docs,
                                     long chunks,
                                     long content,
                                     long chunkText,
                                     long embedding) {
        when(docRepo.countByActiveTrue()).thenReturn(docs, docs);
        when(chunkRepo.countActive()).thenReturn(chunks, chunks);
        when(docRepo.sumMetadataLength()).thenReturn(content, content);
        when(chunkRepo.sumTextLength()).thenReturn(chunkText, chunkText);
        when(vectorRepo.sumEmbeddingLength()).thenReturn(embedding, embedding);
        when(vectorIndexService.estimateIndexBytes()).thenReturn(0L, 0L);
    }

    private KnowledgeDocument document(Long id, String owner, String title, String content) {
        KnowledgeDocument document = new KnowledgeDocument();
        ReflectionTestUtils.setField(document, "id", id);
        document.setOwner(owner);
        document.setTitle(title);
        document.setContent(content);
        return document;
    }

    private KnowledgeChunk chunk(Long id, KnowledgeDocument document, int index, String text) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        ReflectionTestUtils.setField(chunk, "id", id);
        chunk.setDocument(document);
        chunk.setChunkIndex(index);
        chunk.setText(text);
        chunk.setHash("abc123");
        chunk.setTokenCount(6);
        chunk.setSource("api");
        return chunk;
    }

    private RagMaintenanceCase warningCase(Long id, Instant adminDueAt) {
        RagMaintenanceCase ragCase = new RagMaintenanceCase();
        ReflectionTestUtils.setField(ragCase, "id", id);
        ragCase.setDocumentId(id + 1000);
        ragCase.setOwner("global");
        ragCase.setDocumentTitle("Caso " + id);
        ragCase.setSeverity(RagMaintenanceSeverity.WARNING);
        ragCase.setIssueType(RagMaintenanceIssueType.BAD_STRUCTURE);
        ragCase.setStatus(RagMaintenanceCaseStatus.OPEN);
        ragCase.setRecommendedAction(RagMaintenanceAction.RESTRUCTURE);
        ragCase.setAdminDueAt(adminDueAt);
        ragCase.setSummary("Documento util pero mal estructurado.");
        ragCase.setOriginalSnippet("Texto original");
        ragCase.setProposedContent("Texto limpio");
        return ragCase;
    }

    private MonitoringAlertStateDto healthyMonitoring() {
        return new MonitoringAlertStateDto(
                false, false, false, false, false,
                null, null, null, null, null
        );
    }
}
