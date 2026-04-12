package com.example.apiasistente.rag.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RagAdminPageController.class)
@AutoConfigureMockMvc(addFilters = false)
/**
 * Pruebas para Rag Admin Page Controller.
 */
class RagAdminPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void ragAdminPageRedirectsToAngularWorkspace() throws Exception {
        mockMvc.perform(get("/rag-admin")
                        .requestAttr("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-token")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/rag-admin"));
    }
}
