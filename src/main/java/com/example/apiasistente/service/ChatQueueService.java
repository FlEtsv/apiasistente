package com.example.apiasistente.service;

import com.example.apiasistente.config.ChatQueueProperties;
import com.example.apiasistente.model.dto.ChatResponse;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;

/**
 * Cola por sesión: garantiza que los mensajes se procesen en orden de llegada
 * y que una sesión no ejecute más de un turno simultáneo.
 */
@Service
public class ChatQueueService {

    private final ChatService chatService;
    private final ChatQueueProperties properties;
    private final ExecutorService executor;
    private final Map<String, SessionQueue> sessionQueues = new ConcurrentHashMap<>();

    public ChatQueueService(ChatService chatService, ChatQueueProperties properties) {
        this.chatService = chatService;
        this.properties = properties;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Encola el mensaje y devuelve la respuesta cuando el turno termina.
     */
    public ChatResponse chatAndWait(String username, String sessionId, String message, String model) {
        try {
            return enqueueChat(username, sessionId, message, model).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("La cola de chat fue interrumpida.", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Error procesando mensaje en la cola.", e.getCause());
        }
    }

    /**
     * Encola el mensaje y devuelve un future para esperar el resultado.
     */
    public CompletableFuture<ChatResponse> enqueueChat(String username, String sessionId, String message, String model) {
        String queueKey = resolveQueueKey(username, sessionId);
        SessionQueue queue = sessionQueues.computeIfAbsent(queueKey, key -> new SessionQueue());
        QueuedChat queued = new QueuedChat(username, sessionId, message, model);

        queue.enqueue(queued);
        startProcessingIfNeeded(queueKey, queue);

        return queued.response();
    }

    private void startProcessingIfNeeded(String queueKey, SessionQueue queue) {
        if (!queue.markProcessing()) {
            return;
        }

        executor.execute(() -> processQueue(queueKey, queue));
    }

    private void processQueue(String queueKey, SessionQueue queue) {
        try {
            while (true) {
                QueuedChat next = queue.poll();
                if (next == null) {
                    // Evita condiciones de carrera: si entró algo justo después del poll,
                    // no liberamos el procesamiento hasta confirmar que la cola sigue vacía.
                    if (queue.stopProcessingIfIdle()) {
                        cleanupIfIdle(queueKey, queue);
                        return;
                    }
                    continue;
                }

                applyDelay();

                try {
                    ChatResponse response = chatService.chat(
                            next.username(),
                            next.sessionId(),
                            next.message(),
                            next.model()
                    );
                    next.response().complete(response);
                } catch (Exception ex) {
                    next.response().completeExceptionally(ex);
                }
            }
        } finally {
            cleanupIfIdle(queueKey, queue);
        }
    }

    private void applyDelay() {
        long delay = properties.getDelayMs();
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void cleanupIfIdle(String queueKey, SessionQueue queue) {
        if (queue.isIdle()) {
            sessionQueues.remove(queueKey, queue);
        }
    }

    private String resolveQueueKey(String username, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }
        return "new-session::" + Objects.requireNonNull(username, "username requerido");
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Cola interna por sesión (FIFO).
     */
    private static final class SessionQueue {
        private final Deque<QueuedChat> queue = new ArrayDeque<>();
        private boolean processing;

        synchronized void enqueue(QueuedChat chat) {
            queue.addLast(chat);
        }

        synchronized QueuedChat poll() {
            return queue.pollFirst();
        }

        synchronized boolean markProcessing() {
            if (processing) {
                return false;
            }
            processing = true;
            return true;
        }

        /**
         * Detiene el procesamiento sólo si la cola está vacía.
         * Devuelve true si se liberó el flag y la cola quedó vacía.
         */
        synchronized boolean stopProcessingIfIdle() {
            if (!queue.isEmpty()) {
                return false;
            }
            processing = false;
            return true;
        }

        synchronized boolean isIdle() {
            return !processing && queue.isEmpty();
        }
    }

    /**
     * Item de cola con future asociado.
     */
    private record QueuedChat(
            String username,
            String sessionId,
            String message,
            String model,
            CompletableFuture<ChatResponse> response
    ) {
        QueuedChat(String username, String sessionId, String message, String model) {
            this(username, sessionId, message, model, new CompletableFuture<>());
        }
    }
}
