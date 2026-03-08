package com.example.apiasistente.chat.controller;

import com.example.apiasistente.chat.service.ChatRuntimeAdaptationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatRuntimeController.class)
@AutoConfigureMockMvc(addFilters = false)
class ChatRuntimeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatRuntimeAdaptationService runtimeAdaptationService;

    @Test
    void profileReturnsRuntimeSnapshot() throws Exception {
        when(runtimeAdaptationService.currentProfile()).thenReturn(
                new ChatRuntimeAdaptationService.RuntimeProfile(
                        ChatRuntimeAdaptationService.RuntimeMode.CONSTRAINED,
                        true,
                        0.15,
                        1200,
                        "cpu-high,model-latency-high",
                        new ChatRuntimeAdaptationService.RuntimeSnapshot(
                                Instant.parse("2026-03-07T12:00:00Z"),
                                0.91,
                                0.72,
                                true,
                                98,
                                true,
                                0.81,
                                0.66,
                                5210.5,
                                20,
                                4300.2,
                                11,
                                2
                        )
                )
        );

        mockMvc.perform(get("/api/chat/runtime/profile").principal(() -> "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("CONSTRAINED"))
                .andExpect(jsonPath("$.preferFastModel").value(true))
                .andExpect(jsonPath("$.snapshot.gpuAvailable").value(true))
                .andExpect(jsonPath("$.snapshot.pressureScore").value(2));
    }
}
