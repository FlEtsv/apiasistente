package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.ChatResponse;
import com.example.apiasistente.service.ChatQueueService;
import com.example.apiasistente.service.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExternalApiController.class)
@AutoConfigureMockMvc(addFilters = false)
class ExternalApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatQueueService chatQueueService;

    @MockBean
    private RagService ragService;

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
    void chatDelegatesToQueue() throws Exception {
        ChatResponse response = new ChatResponse("sid-1", "hola", List.of());
        when(chatQueueService.chatAndWait(eq("ext-user"), eq("sid-1"), eq("Hola"), eq("fast")))
                .thenReturn(response);

        mockMvc.perform(post("/api/ext/chat")
                        .principal(() -> "ext-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"sid-1","message":"Hola","model":"fast"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sid-1"))
                .andExpect(jsonPath("$.reply").value("hola"));
    }
}
