package com.example.apiasistente.monitoring.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(MonitoringLinksController.class)
@AutoConfigureMockMvc(addFilters = false)
/**
 * Pruebas para Monitoring Links Controller.
 */
class MonitoringLinksControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void grafanaRedirectsToConfiguredBaseUrl() throws Exception {
        mockMvc.perform(get("/ops/grafana"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", endsWith(":3000")));
    }

    @Test
    void prometheusConfigRedirectsToConfigPath() throws Exception {
        mockMvc.perform(get("/ops/prometheus/config"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", endsWith(":9090/config")));
    }

    @Test
    void statusReturnsProbePayload() throws Exception {
        mockMvc.perform(get("/ops/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grafana.name").value("grafana"))
                .andExpect(jsonPath("$.prometheus.name").value("prometheus"));
    }

    @Test
    void statusUiReturnsView() throws Exception {
        mockMvc.perform(get("/ops/status/ui"))
                .andExpect(status().isOk())
                .andExpect(view().name("ops_status"));
    }
}
