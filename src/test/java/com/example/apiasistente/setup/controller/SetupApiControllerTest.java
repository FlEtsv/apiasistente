package com.example.apiasistente.setup.controller;

import com.example.apiasistente.rag.dto.RagMaintenanceStatusDto;
import com.example.apiasistente.rag.service.RagMaintenanceService;
import com.example.apiasistente.rag.service.RagWebScraperService;
import com.example.apiasistente.setup.dto.SetupConfigResponse;
import com.example.apiasistente.setup.service.SetupConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SetupApiController.class)
@AutoConfigureMockMvc(addFilters = false)
class SetupApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SetupConfigService setupConfigService;

    @MockitoBean
    private RagWebScraperService ragWebScraperService;

    @MockitoBean
    private RagMaintenanceService ragMaintenanceService;

    @Test
    void statusReturnsConfiguredFlag() throws Exception {
        when(setupConfigService.isConfigured()).thenReturn(true);

        mockMvc.perform(get("/api/setup/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true));
    }

    @Test
    void configReturnsCurrentSnapshot() throws Exception {
        when(setupConfigService.current()).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/setup/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.ollamaBaseUrl").value("http://localhost:11434/api"))
                .andExpect(jsonPath("$.scraperUrls[0]").value("https://example.com"));
    }

    @Test
    void defaultsReturnsPresetSnapshot() throws Exception {
        when(setupConfigService.defaults()).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/setup/defaults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chatModel").value("qwen3:14b"))
                .andExpect(jsonPath("$.embedModel").value("nomic-embed-text:latest"));
    }

    @Test
    void savePersistsConfiguration() throws Exception {
        when(setupConfigService.save(any())).thenReturn(sampleResponse());

        mockMvc.perform(put("/api/setup/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ollamaBaseUrl":"http://localhost:11434/api",
                                  "chatModel":"qwen3:14b",
                                  "fastChatModel":"qwen2.5:7b",
                                  "embedModel":"nomic-embed-text:latest"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.chatModel").value("qwen3:14b"));
    }

    @Test
    void manualScraperRunReturnsSummary() throws Exception {
        when(ragWebScraperService.scrapeNow()).thenReturn(
                new RagWebScraperService.ScrapeRunResult(true, 4, 2, 1, 1, "ok")
        );

        mockMvc.perform(post("/api/setup/scraper/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executed").value(true))
                .andExpect(jsonPath("$.processed").value(4))
                .andExpect(jsonPath("$.updated").value(2));
    }

    @Test
    void ragRobotStatusShowsPoweredOn() throws Exception {
        when(ragMaintenanceService.status()).thenReturn(ragStatus(true, false, false));

        mockMvc.perform(get("/api/setup/rag-robot/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuredEnabled").value(true))
                .andExpect(jsonPath("$.poweredOn").value(true));
    }

    @Test
    void ragRobotPowerOffPausesRobot() throws Exception {
        when(ragMaintenanceService.pause()).thenReturn(ragStatus(true, true, false));

        mockMvc.perform(post("/api/setup/rag-robot/power?enabled=false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poweredOn").value(false))
                .andExpect(jsonPath("$.paused").value(true));
    }

    private SetupConfigResponse sampleResponse() {
        return new SetupConfigResponse(
                true,
                "http://localhost:11434/api",
                "qwen3:14b",
                "qwen2.5:7b",
                "qwen2.5vl:7b",
                "dreamshaper_8",
                "nomic-embed-text:latest",
                "mistral:7b",
                true,
                List.of("https://example.com"),
                "global",
                "web-scraper",
                "scraper,web,knowledge",
                300000,
                Instant.parse("2026-03-07T13:00:00Z")
        );
    }

    private RagMaintenanceStatusDto ragStatus(boolean schedulerEnabled, boolean paused, boolean running) {
        return new RagMaintenanceStatusDto(
                schedulerEnabled,
                paused,
                false,
                running,
                180000,
                null,
                null,
                null,
                "Idle",
                "",
                null,
                null,
                List.of()
        );
    }
}
