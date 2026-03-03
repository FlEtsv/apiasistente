package com.example.apiasistente.monitoring.controller;

import com.example.apiasistente.monitoring.dto.MonitoringAlertDto;
import com.example.apiasistente.monitoring.dto.MonitoringAlertStateDto;
import com.example.apiasistente.monitoring.dto.ServerStatsDto;
import com.example.apiasistente.monitoring.service.MonitorService;
import com.example.apiasistente.monitoring.service.MonitoringAlertService;
import com.example.apiasistente.monitoring.service.MonitoringAlertStore;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExternalMonitoringController.class)
@AutoConfigureMockMvc(addFilters = false)
/**
 * Pruebas para External Monitoring Controller.
 */
class ExternalMonitoringControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonitorService monitorService;

    @MockitoBean
    private MonitoringAlertService monitoringAlertService;

    @MockitoBean
    private MonitoringAlertStore monitoringAlertStore;

    @Test
    void serverReturnsSnapshot() throws Exception {
        when(monitorService.snapshot()).thenReturn(new ServerStatsDto(
                "srv-ext",
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
                8
        ));

        mockMvc.perform(get("/api/ext/monitor/server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostname").value("srv-ext"));
    }

    @Test
    void alertsReturnsRecentEvents() throws Exception {
        when(monitoringAlertStore.recent(eq(Instant.parse("2026-02-28T10:00:00Z")), eq(5)))
                .thenReturn(List.of(new MonitoringAlertDto(
                        "alert-ext",
                        Instant.parse("2026-02-28T10:01:00Z"),
                        "ALERT",
                        "DISK",
                        "Disco alto",
                        "Disco por encima del umbral",
                        "srv-ext",
                        0.95,
                        0.90,
                        null,
                        null
                )));

        mockMvc.perform(get("/api/ext/monitor/alerts")
                        .param("since", "2026-02-28T10:00:00Z")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("alert-ext"));
    }

    @Test
    void alertStateReturnsCurrentState() throws Exception {
        when(monitoringAlertService.currentState()).thenReturn(new MonitoringAlertStateDto(
                false,
                true,
                false,
                false,
                false,
                null,
                Instant.parse("2026-02-28T10:00:00Z"),
                null,
                null,
                null
        ));

        mockMvc.perform(get("/api/ext/monitor/alerts/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memoryHigh").value(true));
    }
}
