package com.example.apiasistente.rag.controller;

import com.example.apiasistente.rag.dto.RagOpsEventDto;
import com.example.apiasistente.rag.dto.RagOpsStatusDto;
import com.example.apiasistente.rag.service.RagOpsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RagOpsController.class)
@AutoConfigureMockMvc(addFilters = false)
class RagOpsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RagOpsService ragOpsService;

    @Test
    void statusReturnsRagOpsSnapshot() throws Exception {
        when(ragOpsService.status()).thenReturn(statusDto());

        mockMvc.perform(get("/api/rag/ops/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeDocuments").value(12))
                .andExpect(jsonPath("$.activeChunks").value(44))
                .andExpect(jsonPath("$.activeVectors").value(44))
                .andExpect(jsonPath("$.lastRetrievalSummary").value("owners=global candidates=8 context=3 evidence=si retrievalMs=21.4"));
    }

    @Test
    void rebuildEndpointDelegatesToService() throws Exception {
        when(ragOpsService.rebuildIndex("manual-ui")).thenReturn(statusDto());

        mockMvc.perform(post("/api/rag/ops/index/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexRebuilds").value(2));

        verify(ragOpsService).rebuildIndex("manual-ui");
    }

    @Test
    void clearLogsEndpointReturnsSnapshot() throws Exception {
        when(ragOpsService.clearRecentEvents()).thenReturn(statusDto());

        mockMvc.perform(post("/api/rag/ops/logs/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentEvents[0].type").value("INDEX_REBUILD"));
    }

    @Test
    void purgeOldestEndpointDelegatesToService() throws Exception {
        when(ragOpsService.purgeOldestDocuments(30)).thenReturn(statusDto());

        mockMvc.perform(post("/api/rag/ops/documents/purge-oldest?count=30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedDocuments").value(1));

        verify(ragOpsService).purgeOldestDocuments(30);
    }

    private RagOpsStatusDto statusDto() {
        return new RagOpsStatusDto(
                "documents + chunks append-only + vectors persistidos + indice HNSW",
                "C:\\data\\rag-hnsw",
                Instant.parse("2026-03-03T12:03:00Z"),
                Instant.parse("2026-03-03T12:00:00Z"),
                12,
                44,
                44,
                100,
                200,
                300,
                400,
                1000,
                10,
                700,
                120,
                5,
                12,
                0.45,
                3,
                7,
                1,
                5,
                44,
                5,
                2,
                0,
                Instant.parse("2026-03-03T11:59:00Z"),
                "Doc 77 en owner 'global' con 6 chunks.",
                Instant.parse("2026-03-03T12:02:00Z"),
                "owners=global candidates=8 context=3 evidence=si retrievalMs=21.4",
                Instant.parse("2026-03-03T11:58:00Z"),
                "Doc 55 eliminado desde maintenance.",
                Instant.parse("2026-03-03T12:01:00Z"),
                "Reconstruccion completa del indice desde vectors: 44 vectores.",
                List.of(
                        new RagOpsEventDto(
                                Instant.parse("2026-03-03T12:01:00Z"),
                                "WARN",
                                "INDEX_REBUILD",
                                "Reindexado HNSW",
                                "trigger=manual-ui vectores=44"
                        )
                )
        );
    }
}
