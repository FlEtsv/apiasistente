package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.ApiKeyCreateResponse;
import com.example.apiasistente.service.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiKeyController.class)
@AutoConfigureMockMvc(addFilters = false)
class ApiKeyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @Test
    void createReturnsSessionIdForExternalChat() throws Exception {
        ApiKeyCreateResponse response = new ApiKeyCreateResponse(
                10L,
                "ERP",
                "prefix-1",
                "raw-key",
                "session-1"
        );

        when(apiKeyService.createForUser(eq("user"), eq("ERP"))).thenReturn(response);

        mockMvc.perform(post("/api/api-keys")
                        .principal(() -> "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"ERP"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-1"))
                .andExpect(jsonPath("$.apiKey").value("raw-key"));
    }
}
