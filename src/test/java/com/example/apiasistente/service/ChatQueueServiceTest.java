package com.example.apiasistente.service;

import com.example.apiasistente.config.ChatQueueProperties;
import com.example.apiasistente.model.dto.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatQueueServiceTest {

    @Mock
    private ChatService chatService;

    private ChatQueueService queueService;

    @BeforeEach
    void setUp() {
        ChatQueueProperties props = new ChatQueueProperties();
        props.setDelayMs(0);
        queueService = new ChatQueueService(chatService, props);
    }

    @AfterEach
    void tearDown() {
        queueService.shutdown();
    }

    @Test
    void processesMessagesInOrderPerSession() throws Exception {
        when(chatService.chat("user", "sid", "primero", "default", null, List.of()))
                .thenReturn(new ChatResponse("sid", "ok-1", List.of()));
        when(chatService.chat("user", "sid", "segundo", "default", null, List.of()))
                .thenReturn(new ChatResponse("sid", "ok-2", List.of()));

        var first = queueService.enqueueChat("user", "sid", "primero", "default");
        var second = queueService.enqueueChat("user", "sid", "segundo", "default");

        assertEquals("ok-1", first.get(2, TimeUnit.SECONDS).getReply());
        assertEquals("ok-2", second.get(2, TimeUnit.SECONDS).getReply());

        InOrder order = inOrder(chatService);
        order.verify(chatService).chat("user", "sid", "primero", "default", null, List.of());
        order.verify(chatService).chat("user", "sid", "segundo", "default", null, List.of());
    }
}
