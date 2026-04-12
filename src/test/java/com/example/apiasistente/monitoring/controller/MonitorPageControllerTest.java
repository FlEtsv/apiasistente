package com.example.apiasistente.monitoring.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MonitorPageController.class)
@AutoConfigureMockMvc(addFilters = false)
/**
 * Pruebas para Monitor Page Controller.
 */
class MonitorPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void monitorPageRedirectsToAngularWorkspace() throws Exception {
        mockMvc.perform(get("/monitor"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/monitor"));
    }
}
