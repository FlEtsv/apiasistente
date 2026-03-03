package com.example.apiasistente.shared.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(HomeController.class)
@AutoConfigureMockMvc(addFilters = false)
/**
 * Pruebas para Home Controller.
 */
class HomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousUserRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void chatUserRedirectsToChat() throws Exception {
        mockMvc.perform(get("/").principal(new TestingAuthenticationToken("ana", "pw", "PERM_CHAT")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chat"));
    }

    @Test
    void monitorUserRedirectsToMonitor() throws Exception {
        mockMvc.perform(get("/").principal(new TestingAuthenticationToken("ana", "pw", "PERM_MONITOR")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/monitor"));
    }

    @Test
    void ragUserRedirectsToRagAdmin() throws Exception {
        mockMvc.perform(get("/").principal(new TestingAuthenticationToken("ana", "pw", "PERM_RAG")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rag-admin"));
    }

    @Test
    void accessDeniedPageReturnsView() throws Exception {
        mockMvc.perform(get("/access-denied"))
                .andExpect(status().isOk())
                .andExpect(view().name("access_denied"));
    }
}
