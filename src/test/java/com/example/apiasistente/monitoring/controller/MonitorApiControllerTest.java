package com.example.apiasistente.monitoring.controller;

import com.example.apiasistente.monitoring.dto.MonitoringAlertDto;
import com.example.apiasistente.monitoring.dto.MonitoringAlertStateDto;
import com.example.apiasistente.monitoring.dto.MonitoringStackStatusDto;
import com.example.apiasistente.monitoring.dto.ServerStatsDto;
import com.example.apiasistente.monitoring.service.MonitorService;
import com.example.apiasistente.monitoring.service.MonitoringAlertService;
import com.example.apiasistente.monitoring.service.MonitoringAlertStore;
import com.example.apiasistente.monitoring.service.MonitoringStackService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MonitorApiController.class)
@AutoConfigureMockMvc(addFilters = false)
/**
 * Pruebas para Monitor Api Controller.
 */
class MonitorApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonitorService monitorService;

    @MockitoBean
    private MonitoringAlertStore monitoringAlertStore;

    @MockitoBean
    private MonitoringAlertService monitoringAlertService;

    @MockitoBean
    private MonitoringStackService monitoringStackService;

    @Test
    void serverReturnsSnapshot() throws Exception {
        when(monitorService.snapshot()).thenReturn(serverStats());

        mockMvc.perform(get("/api/monitor/server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostname").value("srv-01"));
    }

    @Test
    void alertsReturnsRecentEvents() throws Exception {
        when(monitoringAlertStore.recent(eq(Instant.parse("2026-02-28T10:00:00Z")), eq(5)))
                .thenReturn(List.of(alert()));

        mockMvc.perform(get("/api/monitor/alerts")
                        .param("since", "2026-02-28T10:00:00Z")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("alert-1"));
    }

    @Test
    void alertStateReturnsCurrentState() throws Exception {
        when(monitoringAlertService.currentState()).thenReturn(alertState());

        mockMvc.perform(get("/api/monitor/alerts/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpuHigh").value(true))
                .andExpect(jsonPath("$.internetDown").value(true));
    }

    @Test
    void stackStatusReturnsCurrentState() throws Exception {
        when(monitoringStackService.status()).thenReturn(stackStatus(false, "Stack parcial"));

        mockMvc.perform(get("/api/monitor/stack/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dockerInstalled").value(true))
                .andExpect(jsonPath("$.message").value("Stack parcial"));
    }

    @Test
    void stackUpReturnsActionResult() throws Exception {
        when(monitoringStackService.ensureUp()).thenReturn(stackStatus(true, "Stack de observabilidad activo"));

        mockMvc.perform(post("/api/monitor/stack/up"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionExecuted").value(true))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Stack de observabilidad activo"));
    }

    private ServerStatsDto serverStats() {
        return new ServerStatsDto(
                "srv-01",
                Instant.parse("2026-02-28T10:00:00Z"),
                123,
                new ServerStatsDto.CpuInfo(0.1, 0.05, 1.0),
                new ServerStatsDto.MemoryInfo(10, 20),
                new ServerStatsDto.MemoryInfo(5, 15),
                new ServerStatsDto.MemoryInfo(5, 5),
                new ServerStatsDto.MemoryInfo(100, 200),
                new ServerStatsDto.MemoryInfo(10, 100),
                new ServerStatsDto.DiskInfo(50, 100),
                new ServerStatsDto.ThreadInfo(10, 12, 5),
                new ServerStatsDto.GcInfo(1, 10),
                new ServerStatsDto.NetworkInfo(true, 40, "https://example.test"),
                new ServerStatsDto.GpuInfo(true, 0.42, 0.37, 4600, 12288, "RTX"),
                8
        );
    }

    private MonitoringAlertDto alert() {
        return new MonitoringAlertDto(
                "alert-1",
                Instant.parse("2026-02-28T10:01:00Z"),
                "ALERT",
                "CPU",
                "CPU alto",
                "CPU por encima del umbral",
                "srv-01",
                0.95,
                0.90,
                null,
                null
        );
    }

    private MonitoringAlertStateDto alertState() {
        return new MonitoringAlertStateDto(
                true,
                false,
                false,
                false,
                true,
                Instant.parse("2026-02-28T10:00:00Z"),
                null,
                null,
                null,
                Instant.parse("2026-02-28T10:02:00Z")
        );
    }

    private MonitoringStackStatusDto stackStatus(boolean actionExecuted, String message) {
        return new MonitoringStackStatusDto(
                Instant.parse("2026-02-28T10:02:00Z"),
                actionExecuted,
                true,
                true,
                true,
                true,
                "docker compose",
                "C:/repo/apiasistente",
                "C:/repo/apiasistente/docker-compose.yml",
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                "docker compose -f docker-compose.yml up -d api prometheus grafana",
                message,
                "ok"
        );
    }
}
