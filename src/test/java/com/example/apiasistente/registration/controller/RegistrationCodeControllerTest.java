package com.example.apiasistente.registration.controller;

import com.example.apiasistente.registration.dto.RegistrationCodeCreateResponse;
import com.example.apiasistente.registration.dto.RegistrationCodeDto;
import com.example.apiasistente.registration.service.RegistrationCodeService;
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

@WebMvcTest(RegistrationCodeController.class)
@AutoConfigureMockMvc(addFilters = false)
/**
 * Pruebas para Registration Code Controller.
 */
class RegistrationCodeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegistrationCodeService registrationCodeService;

    @Test
    void listReturnsCurrentUserCodes() throws Exception {
        when(registrationCodeService.listMine(eq("user"))).thenReturn(List.of(
                new RegistrationCodeDto(
                        10L,
                        "Invitacion",
                        "ABC123",
                        Instant.parse("2026-02-28T10:00:00Z"),
                        Instant.parse("2026-02-28T11:00:00Z"),
                        null,
                        null,
                        null,
                        List.of("CHAT", "RAG")
                )
        ));

        mockMvc.perform(get("/api/registration-codes").principal(() -> "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10L))
                .andExpect(jsonPath("$[0].label").value("Invitacion"))
                .andExpect(jsonPath("$[0].permissions[0]").value("CHAT"));
    }

    @Test
    void createReturnsCreatedCode() throws Exception {
        when(registrationCodeService.createForUser(eq("user"), eq("Invitacion"), eq(60), eq(List.of("CHAT", "RAG"))))
                .thenReturn(new RegistrationCodeCreateResponse(
                        10L,
                        "Invitacion",
                        "ABC123",
                        "ABC123-RAW",
                        Instant.parse("2026-02-28T10:00:00Z"),
                        Instant.parse("2026-02-28T11:00:00Z"),
                        List.of("CHAT", "RAG")
                ));

        mockMvc.perform(post("/api/registration-codes")
                        .principal(() -> "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"Invitacion","ttlMinutes":60,"permissions":["CHAT","RAG"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10L))
                .andExpect(jsonPath("$.code").value("ABC123-RAW"))
                .andExpect(jsonPath("$.permissions[1]").value("RAG"));
    }

    @Test
    void revokeDelegatesToService() throws Exception {
        doNothing().when(registrationCodeService).revokeMine(eq("user"), eq(10L));

        mockMvc.perform(delete("/api/registration-codes/10").principal(() -> "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value("true"));
    }
}
