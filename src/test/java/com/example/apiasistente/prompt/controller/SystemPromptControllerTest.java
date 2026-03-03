package com.example.apiasistente.prompt.controller;

import com.example.apiasistente.prompt.entity.SystemPrompt;
import com.example.apiasistente.prompt.repository.SystemPromptRepository;
import com.example.apiasistente.prompt.service.SystemPromptService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SystemPromptController.class)
@AutoConfigureMockMvc(addFilters = false)
/**
 * Pruebas para System Prompt Controller.
 */
class SystemPromptControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SystemPromptRepository systemPromptRepository;

    @MockitoBean
    private SystemPromptService systemPromptService;

    @Test
    void listReturnsPrompts() throws Exception {
        SystemPrompt prompt = new SystemPrompt();
        ReflectionTestUtils.setField(prompt, "id", 1L);
        prompt.setName("default");
        prompt.setContent("Eres un asistente.");
        prompt.setActive(true);
        when(systemPromptRepository.findAll()).thenReturn(List.of(prompt));

        mockMvc.perform(get("/api/system-prompts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1L))
                .andExpect(jsonPath("$[0].name").value("default"))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    void setActiveDelegatesToService() throws Exception {
        doNothing().when(systemPromptService).setActive(eq(7L));

        mockMvc.perform(put("/api/system-prompts/7/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeId").value(7L));
    }
}
