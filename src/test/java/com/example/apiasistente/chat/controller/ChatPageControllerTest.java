package com.example.apiasistente.chat.controller;

import com.example.apiasistente.setup.service.SetupConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(ChatPageController.class)
@AutoConfigureMockMvc(addFilters = false)
/**
 * Pruebas para Chat Page Controller.
 */
class ChatPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SetupConfigService setupConfigService;

    @Test
    void chatPageReturnsView() throws Exception {
        when(setupConfigService.isConfigured()).thenReturn(true);
        mockMvc.perform(get("/chat")
                        .requestAttr("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-token")))
                .andExpect(status().isOk())
                .andExpect(view().name("chat"));
    }

    @Test
    void chatPageRedirectsToSetupWhenNotConfigured() throws Exception {
        when(setupConfigService.isConfigured()).thenReturn(false);
        mockMvc.perform(get("/chat"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/setup"));
    }
}
