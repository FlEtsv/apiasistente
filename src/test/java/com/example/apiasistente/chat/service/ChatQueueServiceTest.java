package com.example.apiasistente.chat.service;

import com.example.apiasistente.chat.config.ChatQueueProperties;
import com.example.apiasistente.chat.dto.ChatResponse;
import com.example.apiasistente.shared.exception.ServiceUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/**
 * Pruebas para Chat Queue Service.
 */
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

    @Test
    void chatAndWaitRethrowsOriginalRuntimeCause() {
        when(chatService.chat("user", "sid", "consulta RAG", "default", null, List.of()))
                .thenThrow(new IllegalStateException("Ollama embed fallo"));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> queueService.chatAndWait("user", "sid", "consulta RAG", "default", null, List.of())
        );

        assertEquals("Ollama embed fallo", error.getMessage());
    }

    @Test
    void serializesSessionlessMessagesPerExternalUserScope() throws Exception {
        when(chatService.chat("owner", null, "primero", "default", "key:7|user:cli-1", List.of()))
                .thenReturn(new ChatResponse("sid-ext", "ok-1", List.of()));
        when(chatService.chat("owner", null, "segundo", "default", "key:7|user:cli-1", List.of()))
                .thenReturn(new ChatResponse("sid-ext", "ok-2", List.of()));

        var first = queueService.enqueueChat("owner", null, "primero", "default", "key:7|user:cli-1");
        var second = queueService.enqueueChat("owner", null, "segundo", "default", "key:7|user:cli-1");

        assertEquals("ok-1", first.get(2, TimeUnit.SECONDS).getReply());
        assertEquals("ok-2", second.get(2, TimeUnit.SECONDS).getReply());

        InOrder order = inOrder(chatService);
        order.verify(chatService).chat("owner", null, "primero", "default", "key:7|user:cli-1", List.of());
        order.verify(chatService).chat("owner", null, "segundo", "default", "key:7|user:cli-1", List.of());
    }

    @Test
    void enqueueChatFailsFastWhenQueueIsShuttingDown() throws Exception {
        queueService.shutdown();

        var future = queueService.enqueueChat("user", "sid", "hola", "default");

        ExecutionException error = assertThrows(
                ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS)
        );
        assertTrue(error.getCause() instanceof ServiceUnavailableException);
    }
}


