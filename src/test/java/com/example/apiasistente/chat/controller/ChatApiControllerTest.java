package com.example.apiasistente.chat.controller;

import com.example.apiasistente.chat.dto.ChatResponse;
import com.example.apiasistente.chat.dto.ChatRagTelemetryEventDto;
import com.example.apiasistente.chat.dto.ChatRagTelemetrySnapshotDto;
import com.example.apiasistente.chat.dto.SessionSummaryDto;
import com.example.apiasistente.chat.service.ChatQueueService;
import com.example.apiasistente.chat.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatApiController.class)
@AutoConfigureMockMvc(addFilters = false)
/**
 * Pruebas para Chat Api Controller.
 */
class ChatApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private ChatQueueService chatQueueService;

    @Test
    void chatAcceptsModelSelection() throws Exception {
        ChatResponse response = new ChatResponse("sid-1", "hola", List.of());
        when(chatQueueService.chatAndWait(eq("user"), eq("sid-1"), eq("Hola"), eq("fast"), isNull(), isNull()))
                .thenReturn(response);

        mockMvc.perform(post("/api/chat")
                        .principal(() -> "user")
                        .contentType(MediaType.APPLICATION_JSON)
                .content("""
                                {"sessionId":"sid-1","message":"Hola","model":"fast"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sid-1"))
                .andExpect(jsonPath("$.reply").value("hola"))
                .andExpect(jsonPath("$.ragUsed").value(false))
                .andExpect(jsonPath("$.ragNeeded").value(false))
                .andExpect(jsonPath("$.reasoningLevel").value("MEDIUM"));
    }

    @Test
    void activeReturnsCurrentSessionId() throws Exception {
        when(chatService.activeSessionId(eq("user"))).thenReturn("sid-active");

        mockMvc.perform(get("/api/chat/active").principal(() -> "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sid-active"));
    }

    @Test
    void ragMetricsReturnsSnapshot() throws Exception {
        when(chatService.ragTelemetry()).thenReturn(new ChatRagTelemetrySnapshotDto(
                Instant.parse("2026-03-03T18:00:00Z"),
                12,
                5,
                7,
                8,
                6,
                4,
                1,
                3,
                1,
                2,
                1,
                0.42,
                0.58,
                0.33,
                0.50,
                0.74,
                0.81,
                0.69,
                182.4,
                41.3,
                List.of(new ChatRagTelemetryEventDto(
                        Instant.parse("2026-03-03T18:00:00Z"),
                        "gate",
                        "attempt",
                        "preferred-metadata-hit",
                        "type=technical"
                ))
        ));

        mockMvc.perform(get("/api/chat/rag/metrics").principal(() -> "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTurns").value(12))
                .andExpect(jsonPath("$.ragUsedTurns").value(5))
                .andExpect(jsonPath("$.avgRetrievalPhaseMs").value(182.4))
                .andExpect(jsonPath("$.recentEvents[0].type").value("gate"));
    }

    @Test
    void newSessionReturnsCreatedSessionId() throws Exception {
        when(chatService.newSession(eq("user"))).thenReturn("sid-new");

        mockMvc.perform(post("/api/chat/sessions").principal(() -> "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sid-new"));
    }

    @Test
    void listSessionsReturnsSummaries() throws Exception {
        when(chatService.listSessions(eq("user"))).thenReturn(List.of(
                new SessionSummaryDto(
                        "sid-1",
                        "Sesion 1",
                        Instant.parse("2026-02-28T10:00:00Z"),
                        Instant.parse("2026-02-28T10:05:00Z"),
                        3L,
                        Instant.parse("2026-02-28T10:05:00Z")
                )
        ));

        mockMvc.perform(get("/api/chat/sessions").principal(() -> "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("sid-1"))
                .andExpect(jsonPath("$[0].title").value("Sesion 1"))
                .andExpect(jsonPath("$[0].messageCount").value(3));
    }

    @Test
    void activateReturnsActivatedSessionId() throws Exception {
        when(chatService.activateSession(eq("user"), eq("sid-1"))).thenReturn("sid-1");

        mockMvc.perform(put("/api/chat/sessions/sid-1/activate").principal(() -> "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sid-1"));
    }

    @Test
    void renameDelegatesToService() throws Exception {
        doNothing().when(chatService).renameSession(eq("user"), eq("sid-1"), eq("Renombrada"));

        mockMvc.perform(put("/api/chat/sessions/sid-1/title")
                        .principal(() -> "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Renombrada"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value("true"));
    }

    @Test
    void deleteDelegatesToService() throws Exception {
        doNothing().when(chatService).deleteSession(eq("user"), eq("sid-1"));

        mockMvc.perform(delete("/api/chat/sessions/sid-1").principal(() -> "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value("true"));
    }

    @Test
    void deleteAllReturnsDeletedCount() throws Exception {
        when(chatService.deleteAllSessions(eq("user"))).thenReturn(4);

        mockMvc.perform(delete("/api/chat/sessions").principal(() -> "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value("4"));
    }

    @Test
    void historyReturnsMessages() throws Exception {
        when(chatService.historyDto(eq("user"), eq("sid-1"))).thenReturn(List.of(
                new com.example.apiasistente.chat.dto.ChatMessageDto(
                        1L,
                        "USER",
                        "Hola",
                        Instant.parse("2026-02-28T10:00:00Z")
                )
        ));

        mockMvc.perform(get("/api/chat/sid-1/history").principal(() -> "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1L))
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[0].content").value("Hola"));
    }
}


