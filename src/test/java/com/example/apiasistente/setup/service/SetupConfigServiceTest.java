package com.example.apiasistente.setup.service;

import com.example.apiasistente.shared.config.OllamaProperties;
import com.example.apiasistente.setup.dto.SetupConfigRequest;
import com.example.apiasistente.setup.repository.AppSetupConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SetupConfigServiceTest {

    private AppSetupConfigRepository repository;
    private SetupConfigService service;

    @BeforeEach
    void setUp() {
        repository = mock(AppSetupConfigRepository.class);
        OllamaProperties defaults = new OllamaProperties();
        defaults.setBaseUrl("http://localhost:11434/api");
        defaults.setChatModel("chat-default");
        defaults.setFastChatModel("fast-default");
        defaults.setVisualModel("visual-default");
        defaults.setImageModel("image-default");
        defaults.setEmbedModel("embed-default");
        defaults.setResponseGuardModel("guard-default");
        service = new SetupConfigService(repository, defaults);
    }

    @Test
    void saveNormalizesBaseUrlAndScraperUrls() {
        when(repository.findTopByOrderByIdAsc()).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SetupConfigRequest request = new SetupConfigRequest();
        request.setOllamaBaseUrl("http://10.0.0.7:11434");
        request.setChatModel("qwen3:14b");
        request.setFastChatModel("qwen2.5:7b");
        request.setEmbedModel("nomic-embed-text");
        request.setScraperEnabled(true);
        request.setScraperUrls("https://a.com,\nhttps://b.com\ninvalido\nhttps://a.com");
        request.setScraperTickMs(5000L);

        var response = service.save(request);

        assertTrue(response.configured());
        assertEquals("http://10.0.0.7:11434/api", response.ollamaBaseUrl());
        assertEquals(2, response.scraperUrls().size());
        assertEquals(10000L, response.scraperTickMs());
    }

    @Test
    void resolvedConfigFallsBackToDefaultsWhenNotConfigured() {
        when(repository.findTopByOrderByIdAsc()).thenReturn(Optional.empty());

        var resolved = service.resolvedOllamaConfig();
        assertEquals("http://localhost:11434/api", resolved.baseUrl());
        assertEquals("chat-default", resolved.chatModel());
        assertEquals("fast-default", resolved.fastChatModel());
    }
}
