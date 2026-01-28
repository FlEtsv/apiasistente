package com.example.apiasistente.service;

import com.example.apiasistente.config.ChatQueueProperties;
import com.example.apiasistente.model.dto.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Test de integración ligero con contexto Spring para validar wiring básico.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        ChatQueueService.class,
        ChatQueueServiceIntegrationTest.TestConfig.class
})
class ChatQueueServiceIntegrationTest {

    @Autowired
    private ChatQueueService queueService;

    @Autowired
    private ChatService chatService;

    @Configuration
    static class TestConfig {
        @Bean
        ChatService chatService() {
            return Mockito.mock(ChatService.class);
        }

        @Bean
        ChatQueueProperties chatQueueProperties() {
            ChatQueueProperties props = new ChatQueueProperties();
            props.setDelayMs(0);
            return props;
        }
    }

    @Test
    void enqueuesWithSpringContext() throws Exception {
        when(chatService.chat("user", "sid", "hola", "fast"))
                .thenReturn(new ChatResponse("sid", "ok", List.of()));

        var response = queueService.enqueueChat("user", "sid", "hola", "fast").get(2, TimeUnit.SECONDS);
        assertEquals("ok", response.getReply());
    }
}
fix: resolve merge conflict in ChatQueueService
