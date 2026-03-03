package com.example.apiasistente.rag.controller;

import com.example.apiasistente.rag.dto.RagMaintenanceCaseDto;
import com.example.apiasistente.rag.dto.RagMaintenanceCorpusDto;
import com.example.apiasistente.rag.dto.RagMaintenanceEventDto;
import com.example.apiasistente.rag.dto.RagMaintenanceRunDto;
import com.example.apiasistente.rag.dto.RagMaintenanceStatusDto;
import com.example.apiasistente.rag.service.RagMaintenanceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RagMaintenanceController.class)
@AutoConfigureMockMvc(addFilters = false)
class RagMaintenanceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RagMaintenanceService ragMaintenanceService;

    @Test
    void statusReturnsRobotSnapshot() throws Exception {
        when(ragMaintenanceService.status()).thenReturn(statusDto());

        mockMvc.perform(get("/api/rag/maintenance/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedulerEnabled").value(true))
                .andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.corpus.totalDocuments").value(12))
                .andExpect(jsonPath("$.lastRun.summary").value("Escaneados 12 docs, casos abiertos 2, decisiones IA 1, autoacciones 0."));
    }

    @Test
    void casesEndpointReturnsPendingQueue() throws Exception {
        when(ragMaintenanceService.listCases(eq(false))).thenReturn(List.of(caseDto()));

        mockMvc.perform(get("/api/rag/maintenance/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documentTitle").value("Doc estructurado"))
                .andExpect(jsonPath("$[0].severity").value("WARNING"))
                .andExpect(jsonPath("$[0].issueType").value("BAD_STRUCTURE"));
    }

    @Test
    void runEndpointTriggersSweep() throws Exception {
        when(ragMaintenanceService.runManualSweep()).thenReturn(statusDto());

        mockMvc.perform(post("/api/rag/maintenance/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastRun.outcome").value("COMPLETED"));
    }

    @Test
    void configEndpointUpdatesDryRunAndInterval() throws Exception {
        RagMaintenanceStatusDto status = new RagMaintenanceStatusDto(
                true,
                false,
                true,
                false,
                60000,
                Instant.parse("2026-03-03T12:00:00Z"),
                Instant.parse("2026-03-03T12:01:00Z"),
                Instant.parse("2026-03-03T12:02:00Z"),
                "Idle",
                "",
                new RagMaintenanceCorpusDto(12, 44, 1000, 2000, 4000, 7000),
                RagMaintenanceRunDto.empty(),
                List.of()
        );
        when(ragMaintenanceService.updateConfig(any())).thenReturn(status);

        mockMvc.perform(post("/api/rag/maintenance/config")
                        .contentType("application/json")
                        .content("""
                                {"dryRun":true,"intervalSeconds":60}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.intervalMs").value(60000));
    }

    @Test
    void decisionEndpointDelegatesToService() throws Exception {
        when(ragMaintenanceService.decideCase(anyLong(), any(), eq("ana"))).thenReturn(caseDto());

        mockMvc.perform(post("/api/rag/maintenance/cases/7/decision")
                        .principal(() -> "ana")
                        .contentType("application/json")
                        .content("""
                                {"action":"DELETE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentTitle").value("Doc estructurado"))
                .andExpect(jsonPath("$.recommendedAction").value("RESTRUCTURE"));
    }

    private RagMaintenanceStatusDto statusDto() {
        return new RagMaintenanceStatusDto(
                true,
                false,
                false,
                false,
                180000,
                Instant.parse("2026-03-03T12:00:00Z"),
                Instant.parse("2026-03-03T12:01:00Z"),
                Instant.parse("2026-03-03T12:04:00Z"),
                "Idle",
                "",
                new RagMaintenanceCorpusDto(12, 44, 1000, 2000, 4000, 7000),
                new RagMaintenanceRunDto(
                        "MANUAL",
                        "COMPLETED",
                        Instant.parse("2026-03-03T12:00:00Z"),
                        Instant.parse("2026-03-03T12:01:00Z"),
                        12,
                        44,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        2048,
                        "Escaneados 12 docs, casos abiertos 2, decisiones IA 1, autoacciones 0."
                ),
                List.of(
                        new RagMaintenanceEventDto(
                                Instant.parse("2026-03-03T12:01:00Z"),
                                "INFO",
                                "RUN_FINISHED",
                                "Barrido completado",
                                "Escaneados 12 docs, casos abiertos 2, decisiones IA 1, autoacciones 0."
                        )
                )
        );
    }

    private RagMaintenanceCaseDto caseDto() {
        return new RagMaintenanceCaseDto(
                7L,
                70L,
                "global",
                "Doc estructurado",
                "WARNING",
                "BAD_STRUCTURE",
                "OPEN",
                "RESTRUCTURE",
                null,
                null,
                0,
                null,
                Instant.parse("2026-03-03T12:00:00Z"),
                Instant.parse("2026-03-03T12:00:00Z"),
                Instant.parse("2026-03-05T12:00:00Z"),
                null,
                null,
                null,
                "Documento util pero mal estructurado.",
                "Snippet",
                "Titulo: Doc estructurado\n\nContenido normalizado:\nHecho 1",
                null,
                null,
                null,
                "[2026-03-03T12:00:00Z] ROBOT - Hallazgo detectado"
        );
    }
}
