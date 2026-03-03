package com.example.apiasistente.apikey.controller;

import com.example.apiasistente.apikey.dto.ApiKeyCreateResponse;
import com.example.apiasistente.apikey.dto.ApiKeyDto;
import com.example.apiasistente.apikey.service.ApiKeyService;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiKeyController.class)
@AutoConfigureMockMvc(addFilters = false)
/**
 * Pruebas para Api Key Controller.
 */
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
                true,
                "raw-key",
                "session-1"
        );

        when(apiKeyService.createForUser(eq("user"), eq("ERP"), eq(false))).thenReturn(response);

        mockMvc.perform(post("/api/api-keys")
                        .principal(() -> "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"ERP"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-1"))
                .andExpect(jsonPath("$.specialModeEnabled").value(true))
                .andExpect(jsonPath("$.apiKey").value("raw-key"));
    }

    @Test
    void listReturnsCurrentUserKeys() throws Exception {
        when(apiKeyService.listMine(eq("user"))).thenReturn(List.of(
                new ApiKeyDto(10L, "ERP", "prefix-1", true, Instant.parse("2026-02-28T10:00:00Z"), null, null)
        ));

        mockMvc.perform(get("/api/api-keys").principal(() -> "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10L))
                .andExpect(jsonPath("$[0].label").value("ERP"))
                .andExpect(jsonPath("$[0].specialModeEnabled").value(true));
    }

    @Test
    void revokeDelegatesToService() throws Exception {
        doNothing().when(apiKeyService).revokeMine(eq("user"), eq(10L));

        mockMvc.perform(delete("/api/api-keys/10").principal(() -> "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value("true"));
    }
}


