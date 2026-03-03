package com.example.apiasistente.chat.integration;

import com.example.apiasistente.apikey.service.ApiKeyService;
import com.example.apiasistente.chat.dto.ChatResponse;
import com.example.apiasistente.chat.service.ChatQueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
/**
 * Pruebas de integracion para External Chat Api.
 */
class ExternalChatApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private ChatQueueService chatQueueService;

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
        stubApiKey("valid-token", 11L, false);

        ChatResponse response = new ChatResponse("sid-3", "hola", List.of(), true, 0.91, 0, false, true, "high");
        when(chatQueueService.chatAndWait(eq("ext-user"), eq("sid-3"), eq("Hola"), eq("fast"), isNull(), isNull()))
                .thenReturn(response);

        mockMvc.perform(post("/api/ext/chat")
                        .header("X-API-KEY", "valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"sid-3","message":"Hola","model":"fast"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sid-3"))
                .andExpect(jsonPath("$.reply").value("hola"))
                .andExpect(jsonPath("$.ragUsed").value(false))
                .andExpect(jsonPath("$.ragNeeded").value(true))
                .andExpect(jsonPath("$.reasoningLevel").value("HIGH"));
    }

    @Test
    void chatRejectsSpecialModeWhenApiKeyIsNotSpecial() throws Exception {
        stubApiKey("generic-token", 12L, false);

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
        stubApiKey("special-token", 99L, true);

        ChatResponse response = new ChatResponse("sid-special", "hola especial", List.of(), true, 0.77, 0, false, false, "low");
        when(chatQueueService.chatAndWait(
                eq("ext-user"),
                isNull(),
                eq("Hola"),
                eq("fast"),
                eq("key:99|user:cliente-7"),
                isNull()
        )).thenReturn(response);

        mockMvc.perform(post("/api/ext/chat")
                        .header("X-API-KEY", "special-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"Hola","model":"fast","specialMode":true,"externalUserId":"cliente-7"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sid-special"))
                .andExpect(jsonPath("$.reply").value("hola especial"))
                .andExpect(jsonPath("$.ragNeeded").value(false))
                .andExpect(jsonPath("$.reasoningLevel").value("LOW"));
    }

    private void stubApiKey(String token, long keyId, boolean specialModeEnabled) {
        when(apiKeyService.authenticate(eq(token)))
                .thenReturn(new ApiKeyService.ApiKeyAuthResult(
                        keyId,
                        "ext-user",
                        specialModeEnabled ? "finanzas-special" : "finanzas-generic",
                        specialModeEnabled
                ));
    }
}
