package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.ChatResponse;
import com.example.apiasistente.service.ApiKeyService;
import com.example.apiasistente.service.ChatQueueService;
import com.example.apiasistente.service.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ExternalApiControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ApiKeyService apiKeyService;

    @MockBean
    private ChatQueueService chatQueueService;

    @MockBean
    private RagService ragService;

    @Test
    void chatRequiresApiKeyAuthentication() throws Exception {
        mockMvc.perform(post("/api/ext/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"sid-2","message":"Hola","model":"fast"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void chatAcceptsValidApiKey() throws Exception {
        when(apiKeyService.authenticateAndGetUsername(eq("valid-token"))).thenReturn("ext-user");
        ChatResponse response = new ChatResponse("sid-3", "hola", List.of());
        when(chatQueueService.chatAndWait(eq("ext-user"), eq("sid-3"), eq("Hola"), eq("fast")))
                .thenReturn(response);

        mockMvc.perform(post("/api/ext/chat")
                        .header("X-API-KEY", "valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"sid-3","message":"Hola","model":"fast"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sid-3"))
                .andExpect(jsonPath("$.reply").value("hola"));
    }
}
