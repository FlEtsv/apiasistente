package com.example.apiasistente.monitoring.controller;

import com.example.apiasistente.monitoring.dto.MonitoringAlertDto;
import com.example.apiasistente.monitoring.dto.MonitoringAlertStateDto;
import com.example.apiasistente.monitoring.dto.ServerStatsDto;
import com.example.apiasistente.apikey.service.ApiKeyService;
import com.example.apiasistente.monitoring.service.MonitorService;
import com.example.apiasistente.monitoring.service.MonitoringAlertService;
import com.example.apiasistente.monitoring.service.MonitoringAlertStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
/**
 * Pruebas de integracion para Integration Monitoring Controller.
 */
class IntegrationMonitoringControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private MonitorService monitorService;

    @MockitoBean
    private MonitoringAlertService monitoringAlertService;

    @MockitoBean
    private MonitoringAlertStore monitoringAlertStore;

    @Test
    void integrationMonitorRejectsMissingApiKey() throws Exception {
        mockMvc.perform(get("/api/integration/monitor/server")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void integrationMonitorAcceptsValidApiKey() throws Exception {
        stubApiKey("valid-token");

        ServerStatsDto dto = new ServerStatsDto(
                "srv-01",
                Instant.parse("2026-02-27T18:00:00Z"),
                1200,
                new ServerStatsDto.CpuInfo(0.2, 0.1, 1.5),
                new ServerStatsDto.MemoryInfo(100, 200),
                new ServerStatsDto.MemoryInfo(50, 150),
                new ServerStatsDto.MemoryInfo(50, 50),
                new ServerStatsDto.MemoryInfo(1024, 2048),
                new ServerStatsDto.MemoryInfo(0, 1024),
                new ServerStatsDto.DiskInfo(10_000, 20_000),
                new ServerStatsDto.ThreadInfo(20, 30, 10),
                new ServerStatsDto.GcInfo(3, 200),
                new ServerStatsDto.NetworkInfo(true, 50, "https://www.gstatic.com/generate_204"),
                new ServerStatsDto.GpuInfo(true, 0.33, 0.44, 8000, 24576, "RTX 3090"),
                8
        );
        when(monitorService.snapshot()).thenReturn(dto);

        mockMvc.perform(get("/api/integration/monitor/server")
                        .header("X-API-KEY", "valid-token")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostname").value("srv-01"))
                .andExpect(jsonPath("$.availableProcessors").value(8));
    }

    @Test
    void integrationMonitorReturnsRecentAlerts() throws Exception {
        stubApiKey("valid-token");
        Instant since = Instant.parse("2026-02-27T18:00:00Z");
        MonitoringAlertDto alert = new MonitoringAlertDto(
                "alert-1",
                Instant.parse("2026-02-27T18:05:00Z"),
                "ALERT",
                "CPU",
                "CPU ALTO",
                "Carga por encima del umbral",
                "srv-01",
                0.95,
                0.90,
                null,
                null
        );
        when(monitoringAlertStore.recent(eq(since), eq(10))).thenReturn(java.util.List.of(alert));

        mockMvc.perform(get("/api/integration/monitor/alerts")
                        .header("X-API-KEY", "valid-token")
                        .param("since", since.toString())
                        .param("limit", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("alert-1"))
                .andExpect(jsonPath("$[0].key").value("CPU"))
                .andExpect(jsonPath("$[0].threshold").value(0.9));
    }

    @Test
    void integrationMonitorRejectsInvalidSinceParameter() throws Exception {
        stubApiKey("valid-token");

        mockMvc.perform(get("/api/integration/monitor/alerts")
                        .header("X-API-KEY", "valid-token")
                        .param("since", "ayer")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void integrationMonitorReturnsAlertState() throws Exception {
        stubApiKey("valid-token");
        MonitoringAlertStateDto state = new MonitoringAlertStateDto(
                true,
                false,
                true,
                false,
                true,
                Instant.parse("2026-02-27T18:01:00Z"),
                null,
                Instant.parse("2026-02-27T18:02:00Z"),
                null,
                Instant.parse("2026-02-27T18:03:00Z")
        );
        when(monitoringAlertService.currentState()).thenReturn(state);

        mockMvc.perform(get("/api/integration/monitor/alerts/state")
                        .header("X-API-KEY", "valid-token")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpuHigh").value(true))
                .andExpect(jsonPath("$.diskHigh").value(true))
                .andExpect(jsonPath("$.internetDown").value(true));
    }

    private void stubApiKey(String token) {
        when(apiKeyService.authenticate(eq(token)))
                .thenReturn(new ApiKeyService.ApiKeyAuthResult(11L, "ext-user", "integration-monitor", false));
    }
}


