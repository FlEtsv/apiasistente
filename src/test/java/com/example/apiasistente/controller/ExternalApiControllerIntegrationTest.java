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
import static org.mockito.ArgumentMatchers.isNull;
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
    void chatAcceptsValidGenericApiKey() throws Exception {
        when(apiKeyService.authenticate(eq("valid-token")))
                .thenReturn(new ApiKeyService.ApiKeyAuthResult(11L, "ext-user", "finanzas-generic", false));

        ChatResponse response = new ChatResponse("sid-3", "hola", List.of());
        when(chatQueueService.chatAndWait(eq("ext-user"), eq("sid-3"), eq("Hola"), eq("fast"), isNull()))
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

    @Test
    void chatRejectsSpecialModeWhenApiKeyIsNotSpecial() throws Exception {
        when(apiKeyService.authenticate(eq("generic-token")))
                .thenReturn(new ApiKeyService.ApiKeyAuthResult(12L, "ext-user", "finanzas-generic", false));

        mockMvc.perform(post("/api/ext/chat")
                        .header("X-API-KEY", "generic-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"Hola","model":"fast","specialMode":true,"externalUserId":"cli-1"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void chatUsesScopedExternalUserWithSpecialApiKey() throws Exception {
        when(apiKeyService.authenticate(eq("special-token")))
                .thenReturn(new ApiKeyService.ApiKeyAuthResult(99L, "ext-user", "finanzas-special", true));

        ChatResponse response = new ChatResponse("sid-special", "hola especial", List.of());
        when(chatQueueService.chatAndWait(
                eq("ext-user"),
                isNull(),
                eq("Hola"),
                eq("fast"),
                eq("key:99|user:cliente-7")
        )).thenReturn(response);

        mockMvc.perform(post("/api/ext/chat")
                        .header("X-API-KEY", "special-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"Hola","model":"fast","specialMode":true,"externalUserId":"cliente-7"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sid-special"))
                .andExpect(jsonPath("$.reply").value("hola especial"));
    }
}
