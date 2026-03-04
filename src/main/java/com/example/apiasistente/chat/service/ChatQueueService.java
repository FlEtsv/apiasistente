package com.example.apiasistente.chat.service;

import com.example.apiasistente.chat.config.ChatQueueProperties;
import com.example.apiasistente.chat.dto.ChatMediaInput;
import com.example.apiasistente.chat.dto.ChatResponse;
import com.example.apiasistente.shared.util.RequestIdHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;

/**
 * Serializa turnos de chat por clave de sesion.
 * Su responsabilidad es evitar ejecucion concurrente sobre un mismo historial y preservar el orden FIFO.
 */
@Service
public class ChatQueueService {

    private static final Logger log = LoggerFactory.getLogger(ChatQueueService.class);

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
     * Encola un turno y bloquea hasta que el pipeline completo devuelve una respuesta.
     */
    public ChatResponse chatAndWait(String username, String sessionId, String message, String model) {
        return chatAndWait(username, sessionId, message, model, null, List.of());
    }

    /**
     * Variante sincronica para integraciones externas con aislamiento por usuario final.
     */
    public ChatResponse chatAndWait(String username,
                                    String sessionId,
                                    String message,
                                    String model,
                                    String externalUserId) {
        return chatAndWait(username, sessionId, message, model, externalUserId, List.of());
    }

    /**
     * Variante sincronica completa con soporte de adjuntos.
     */
    public ChatResponse chatAndWait(String username,
                                    String sessionId,
                                    String message,
                                    String model,
                                    String externalUserId,
                                    List<ChatMediaInput> media) {
        try {
            return enqueueChat(username, sessionId, message, model, externalUserId, media).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("La cola de chat fue interrumpida.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.error(
                    "chat_queue_wait_failed requestId={} sessionId={} externalUserId={} model={} mediaCount={} messagePreview={} causeType={} cause={}",
                    RequestIdHolder.ensure(),
                    safe(sessionId),
                    safe(externalUserId),
                    safe(model),
                    media == null ? 0 : media.size(),
                    preview(message),
                    cause == null ? "" : cause.getClass().getSimpleName(),
                    cause == null ? "sin detalle" : safe(cause.getMessage())
            );
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Error procesando mensaje en la cola.", cause);
        }
    }

    /**
     * Encola el turno y devuelve un future para espera no bloqueante.
     */
    public CompletableFuture<ChatResponse> enqueueChat(String username, String sessionId, String message, String model) {
        return enqueueChat(username, sessionId, message, model, null, List.of());
    }

    /**
     * Variante asincrona con aislamiento por usuario final.
     */
    public CompletableFuture<ChatResponse> enqueueChat(String username,
                                                       String sessionId,
                                                       String message,
                                                       String model,
                                                       String externalUserId) {
        return enqueueChat(username, sessionId, message, model, externalUserId, List.of());
    }

    /**
     * Registra el turno en la cola adecuada y dispara el worker si la sesion esta inactiva.
     */
    public CompletableFuture<ChatResponse> enqueueChat(String username,
                                                       String sessionId,
                                                       String message,
                                                       String model,
                                                       String externalUserId,
                                                       List<ChatMediaInput> media) {
        String queueKey = resolveQueueKey(username, sessionId, externalUserId);
        SessionQueue queue = sessionQueues.computeIfAbsent(queueKey, key -> new SessionQueue());
        String requestId = RequestIdHolder.ensure();
        QueuedChat queued = new QueuedChat(username, sessionId, message, model, externalUserId, media, requestId);
        log.info(
                "chat_queue_enqueued requestId={} queueKey={} sessionId={} externalUserId={} model={} mediaCount={} messagePreview={}",
                requestId,
                queueKey,
                safe(sessionId),
                safe(externalUserId),
                safe(model),
                media == null ? 0 : media.size(),
                preview(message)
        );

        // La cola por sesion garantiza que dos turnos del mismo contexto no compitan por historial o persistencia.
        queue.enqueue(queued);
        startProcessingIfNeeded(queueKey, queue);

        return queued.response();
    }

    /**
     * Arranca un unico worker por cola cuando no hay procesamiento en curso.
     */
    private void startProcessingIfNeeded(String queueKey, SessionQueue queue) {
        if (!queue.markProcessing()) {
            return;
        }

        executor.execute(() -> processQueue(queueKey, queue));
    }

    /**
     * Consume la cola FIFO de una sesion y ejecuta el pipeline real de chat.
     */
    private void processQueue(String queueKey, SessionQueue queue) {
        try {
            while (true) {
                QueuedChat next = queue.poll();
                if (next == null) {
                    // Evita condiciones de carrera: si entrÃ³ algo justo despuÃ©s del poll,
                    // no liberamos el procesamiento hasta confirmar que la cola sigue vacÃ­a.
                    if (queue.stopProcessingIfIdle()) {
                        cleanupIfIdle(queueKey, queue);
                        return;
                    }
                    continue;
                }

                // El delay opcional ayuda a suavizar picos o limitaciones del proveedor.
                applyDelay();

                try (var ignored = RequestIdHolder.use(next.requestId())) {
                    log.info(
                            "chat_queue_processing requestId={} queueKey={} sessionId={} externalUserId={} model={} mediaCount={} messagePreview={}",
                            next.requestId(),
                            queueKey,
                            safe(next.sessionId()),
                            safe(next.externalUserId()),
                            safe(next.model()),
                            next.media() == null ? 0 : next.media().size(),
                            preview(next.message())
                    );
                    // Toda la logica de negocio vive en ChatService/ChatTurnService; la cola solo ordena y propaga contexto.
                    ChatResponse response = chatService.chat(
                            next.username(),
                            next.sessionId(),
                            next.message(),
                            next.model(),
                            next.externalUserId(),
                            next.media()
                    );
                    log.info(
                            "chat_queue_completed requestId={} queueKey={} responseSessionId={} ragUsed={} ragNeeded={} replyPreview={}",
                            next.requestId(),
                            queueKey,
                            response == null ? "" : safe(response.getSessionId()),
                            response != null && response.isRagUsed(),
                            response != null && response.isRagNeeded(),
                            response == null ? "" : preview(response.getReply())
                    );
                    next.response().complete(response);
                } catch (Exception ex) {
                    log.error(
                            "chat_queue_failed requestId={} queueKey={} sessionId={} externalUserId={} model={} mediaCount={} messagePreview={} causeType={} cause={}",
                            next.requestId(),
                            queueKey,
                            safe(next.sessionId()),
                            safe(next.externalUserId()),
                            safe(next.model()),
                            next.media() == null ? 0 : next.media().size(),
                            preview(next.message()),
                            ex.getClass().getSimpleName(),
                            safe(ex.getMessage()),
                            ex
                    );
                    next.response().completeExceptionally(ex);
                } finally {
                    RequestIdHolder.clear();
                }
            }
        } finally {
            cleanupIfIdle(queueKey, queue);
        }
    }

    /**
     * Aplica una espera artificial configurable entre turnos de la misma cola.
     */
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

    /**
     * Elimina la cola del mapa global cuando ya no tiene trabajo pendiente.
     */
    private void cleanupIfIdle(String queueKey, SessionQueue queue) {
        if (queue.isIdle()) {
            sessionQueues.remove(queueKey, queue);
        }
    }

    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 120).trim() + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Resuelve la clave de serializacion del turno.
     * Prioriza la sesion explicita y usa claves sinteticas para nuevas sesiones.
     */
    private String resolveQueueKey(String username, String sessionId, String externalUserId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }
        if (externalUserId != null && !externalUserId.isBlank()) {
            return "new-session::" + Objects.requireNonNull(username, "username requerido")
                    + "::ext::" + externalUserId.trim();
        }
        return "new-session::" + Objects.requireNonNull(username, "username requerido");
    }

    /**
     * Libera el executor cuando el bean se destruye.
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Cola interna por sesiÃ³n (FIFO).
     */
    private static final class SessionQueue {
        private final Deque<QueuedChat> queue = new ArrayDeque<>();
        private boolean processing;

        /**
         * Inserta el turno al final para mantener orden FIFO.
         */
        synchronized void enqueue(QueuedChat chat) {
            queue.addLast(chat);
        }

        /**
         * Toma el siguiente turno listo para procesar.
         */
        synchronized QueuedChat poll() {
            return queue.pollFirst();
        }

        /**
         * Marca la cola como activa si ningun worker la esta atendiendo.
         */
        synchronized boolean markProcessing() {
            if (processing) {
                return false;
            }
            processing = true;
            return true;
        }

        /**
         * Detiene el procesamiento sÃ³lo si la cola estÃ¡ vacÃ­a.
         * Devuelve true si se liberÃ³ el flag y la cola quedÃ³ vacÃ­a.
        (fix)
         */
        synchronized boolean stopProcessingIfIdle() {
            if (!queue.isEmpty()) {
                return false;
            }
            processing = false;
            return true;
        }

        /**
         * Indica si la cola no tiene trabajo pendiente ni worker activo.
         */
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
            String externalUserId,
            List<ChatMediaInput> media,
            String requestId,
            CompletableFuture<ChatResponse> response
    ) {
        QueuedChat(String username, String sessionId, String message, String model) {
            this(username, sessionId, message, model, null, List.of(), RequestIdHolder.ensure(), new CompletableFuture<>());
        }

        QueuedChat(String username, String sessionId, String message, String model, String requestId) {
            this(username, sessionId, message, model, null, List.of(), requestId, new CompletableFuture<>());
        }

        QueuedChat(String username,
                   String sessionId,
                   String message,
                   String model,
                   String externalUserId,
                   List<ChatMediaInput> media,
                   String requestId) {
            this(
                    username,
                    sessionId,
                    message,
                    model,
                    externalUserId,
                    media == null ? List.of() : Collections.unmodifiableList(List.copyOf(media)),
                    requestId,
                    new CompletableFuture<>()
            );
        }
    }
}



