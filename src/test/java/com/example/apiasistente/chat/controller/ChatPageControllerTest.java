package com.example.apiasistente.chat.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(ChatPageController.class)
@AutoConfigureMockMvc(addFilters = false)
class ChatPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void chatPageRedirectsToAngularWorkspace() throws Exception {
        mockMvc.perform(get("/chat")
                        .requestAttr("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-token")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/chat"));
    }

    @Test
    void legacyChatPageReturnsView() throws Exception {
        mockMvc.perform(get("/chat/legacy")
                        .requestAttr("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-token")))
                .andExpect(status().isOk())
                .andExpect(view().name("chat"));
    }
}
