package com.example.apiasistente.chat.controller;

import com.example.apiasistente.chat.dto.ChatResponse;
import com.example.apiasistente.chat.service.ChatQueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
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

@WebMvcTest(ExternalChatController.class)
@AutoConfigureMockMvc(addFilters = false)
/**
 * Pruebas para External Chat Controller.
 */
class ExternalChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatQueueService chatQueueService;

    @Test
    void chatRejectsMissingPrincipal() throws Exception {
        mockMvc.perform(post("/api/ext/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"sid-1","message":"Hola","model":"fast"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void chatDelegatesToQueueInGenericMode() throws Exception {
        ChatResponse response = new ChatResponse("sid-1", "hola", List.of(), true, 0.84, 0, false, true, "high");
        when(chatQueueService.chatAndWait(eq("ext-user"), eq("sid-1"), eq("Hola"), eq("fast"), isNull(), isNull()))
                .thenReturn(response);

        mockMvc.perform(post("/api/ext/chat")
                        .principal(() -> "ext-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"sid-1","message":"Hola","model":"fast"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sid-1"))
                .andExpect(jsonPath("$.reply").value("hola"))
                .andExpect(jsonPath("$.ragUsed").value(false))
                .andExpect(jsonPath("$.ragNeeded").value(true))
                .andExpect(jsonPath("$.reasoningLevel").value("HIGH"))
                .andExpect(jsonPath("$.confidence").value(0.84));
    }

    @Test
    void chatRejectsSpecialModeWithoutSpecialKey() throws Exception {
        mockMvc.perform(post("/api/ext/chat")
                        .principal(() -> "ext-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"Hola","model":"fast","specialMode":true,"externalUserId":"cli-42"}
                                """))
                .andExpect(status().isForbidden());
    }
}
