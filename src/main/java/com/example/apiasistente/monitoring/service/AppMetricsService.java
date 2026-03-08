package com.example.apiasistente.monitoring.service;

import com.example.apiasistente.chat.dto.ChatResponse;
import com.example.apiasistente.chat.service.ChatProcessRouter;
import com.example.apiasistente.chat.service.ChatRuntimeAdaptationService;
import com.example.apiasistente.rag.dto.RagMaintenanceRunDto;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Centraliza metricas de negocio para Prometheus/Grafana.
 * Evita acoplar logica de medicion dentro de cada flujo funcional.
 */
@Service
public class AppMetricsService {

    private static final String NAME_CHAT_REQUESTS = "apiasistente.chat.requests";
    private static final String NAME_CHAT_TURNS = "apiasistente.chat.turns";
    private static final String NAME_CHAT_TURN_DURATION = "apiasistente.chat.turn.duration";
    private static final String NAME_CHAT_TURN_FAILURES = "apiasistente.chat.turn.failures";
    private static final String NAME_CHAT_TURN_CONFIDENCE = "apiasistente.chat.turn.confidence";
    private static final String NAME_CHAT_TURN_GROUNDED = "apiasistente.chat.turn.grounded.sources";
    private static final String NAME_CHAT_MEDIA_ITEMS = "apiasistente.chat.media.items";

    private static final String NAME_QUEUE_ENQUEUED = "apiasistente.chat.queue.enqueued";
    private static final String NAME_QUEUE_COMPLETED = "apiasistente.chat.queue.completed";
    private static final String NAME_QUEUE_FAILED = "apiasistente.chat.queue.failed";
    private static final String NAME_QUEUE_LATENCY = "apiasistente.chat.queue.latency";
    private static final String NAME_QUEUE_ACTIVE = "apiasistente.chat.queue.active.sessions";
    private static final String NAME_QUEUE_PENDING = "apiasistente.chat.queue.pending.messages";

    private static final String NAME_RUNTIME_MODE_TRANSITIONS = "apiasistente.runtime.mode.transitions";
    private static final String NAME_RUNTIME_MODE_GAUGE = "apiasistente.runtime.mode";
    private static final String NAME_RUNTIME_PREFER_FAST_GAUGE = "apiasistente.runtime.prefer.fast";
    private static final String NAME_RUNTIME_PRESSURE_GAUGE = "apiasistente.runtime.pressure.score";
    private static final String NAME_RUNTIME_CPU_GAUGE = "apiasistente.runtime.cpu.load.ratio";
    private static final String NAME_RUNTIME_MEMORY_GAUGE = "apiasistente.runtime.memory.load.ratio";
    private static final String NAME_RUNTIME_GPU_GAUGE = "apiasistente.runtime.gpu.load.ratio";
    private static final String NAME_RUNTIME_GPU_MEMORY_GAUGE = "apiasistente.runtime.gpu.memory.load.ratio";
    private static final String NAME_RUNTIME_NETWORK_UP_GAUGE = "apiasistente.runtime.network.up";
    private static final String NAME_RUNTIME_NETWORK_LATENCY_GAUGE = "apiasistente.runtime.network.latency.ms";
    private static final String NAME_RUNTIME_MODEL_LATENCY_GAUGE = "apiasistente.runtime.model.latency.ms";
    private static final String NAME_RUNTIME_TURN_LATENCY_GAUGE = "apiasistente.runtime.turn.latency.ms";

    private static final String NAME_RAG_RUNS = "apiasistente.rag.maintenance.runs";
    private static final String NAME_RAG_RUN_DURATION = "apiasistente.rag.maintenance.run.duration";
    private static final String NAME_RAG_RUNNING_GAUGE = "apiasistente.rag.maintenance.running";
    private static final String NAME_RAG_PAUSED_GAUGE = "apiasistente.rag.maintenance.paused";
    private static final String NAME_RAG_DRY_RUN_GAUGE = "apiasistente.rag.maintenance.dry.run";
    private static final String NAME_RAG_LAST_SCANNED_DOCS_GAUGE = "apiasistente.rag.maintenance.last.scanned.documents";
    private static final String NAME_RAG_LAST_SCANNED_CHUNKS_GAUGE = "apiasistente.rag.maintenance.last.scanned.chunks";
    private static final String NAME_RAG_LAST_REBUILT_DOCS_GAUGE = "apiasistente.rag.maintenance.last.rebuilt.documents";
    private static final String NAME_RAG_LAST_DELETED_DOCS_GAUGE = "apiasistente.rag.maintenance.last.deleted.documents";
    private static final String NAME_RAG_LAST_DELETED_CHUNKS_GAUGE = "apiasistente.rag.maintenance.last.deleted.chunks";
    private static final String NAME_RAG_LAST_PRUNED_CHUNKS_GAUGE = "apiasistente.rag.maintenance.last.pruned.chunks";
    private static final String NAME_RAG_LAST_FREED_BYTES_GAUGE = "apiasistente.rag.maintenance.last.freed.bytes";

    private final MeterRegistry meterRegistry;

    private final AtomicInteger queueActiveSessions = new AtomicInteger(0);
    private final AtomicInteger queuePendingMessages = new AtomicInteger(0);

    private final AtomicInteger runtimeModeCode = new AtomicInteger(0);
    private final AtomicInteger runtimePreferFast = new AtomicInteger(0);
    private final AtomicInteger runtimePressureScore = new AtomicInteger(0);
    private final AtomicInteger runtimeNetworkUp = new AtomicInteger(1);
    private final AtomicReference<Double> runtimeCpuLoad = new AtomicReference<>(0.0);
    private final AtomicReference<Double> runtimeMemoryLoad = new AtomicReference<>(0.0);
    private final AtomicReference<Double> runtimeGpuLoad = new AtomicReference<>(0.0);
    private final AtomicReference<Double> runtimeGpuMemoryLoad = new AtomicReference<>(0.0);
    private final AtomicReference<Double> runtimeNetworkLatencyMs = new AtomicReference<>(0.0);
    private final AtomicReference<Double> runtimeModelLatencyMs = new AtomicReference<>(0.0);
    private final AtomicReference<Double> runtimeTurnLatencyMs = new AtomicReference<>(0.0);
    private final AtomicReference<String> lastRuntimeMode = new AtomicReference<>("normal");

    private final AtomicInteger ragRunning = new AtomicInteger(0);
    private final AtomicInteger ragPaused = new AtomicInteger(0);
    private final AtomicInteger ragDryRun = new AtomicInteger(0);
    private final AtomicReference<Double> ragLastScannedDocuments = new AtomicReference<>(0.0);
    private final AtomicReference<Double> ragLastScannedChunks = new AtomicReference<>(0.0);
    private final AtomicReference<Double> ragLastRebuiltDocuments = new AtomicReference<>(0.0);
    private final AtomicReference<Double> ragLastDeletedDocuments = new AtomicReference<>(0.0);
    private final AtomicReference<Double> ragLastDeletedChunks = new AtomicReference<>(0.0);
    private final AtomicReference<Double> ragLastPrunedChunks = new AtomicReference<>(0.0);
    private final AtomicReference<Double> ragLastFreedBytes = new AtomicReference<>(0.0);

    public AppMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        registerGauges();
    }

    /**
     * Registra metrica de enrutado inicial de una peticion de chat.
     *
     * @param decision decision del router de proceso
     * @param mediaCount numero total de adjuntos en la peticion
     * @param hasImageMedia si contiene media de tipo imagen
     * @param hasDocumentMedia si contiene media documental
     */
    public void recordChatRoute(ChatProcessRouter.ProcessDecision decision,
                                int mediaCount,
                                boolean hasImageMedia,
                                boolean hasDocumentMedia) {
        if (decision == null) {
            return;
        }
        String route = normalizeTag(decision.route().name(), "chat");
        String pipeline = normalizeTag(decision.pipeline().name(), "chat_fast");
        String source = normalizeTag(decision.source(), "heuristic");

        meterRegistry.counter(
                NAME_CHAT_REQUESTS,
                "route", route,
                "pipeline", pipeline,
                "source", source,
                "used_llm", boolTag(decision.usedLlm()),
                "has_media", boolTag(mediaCount > 0),
                "has_image_media", boolTag(hasImageMedia),
                "has_document_media", boolTag(hasDocumentMedia)
        ).increment();

        meterRegistry.summary(NAME_CHAT_MEDIA_ITEMS).record(Math.max(0, mediaCount));
    }

    /**
     * Registra una finalizacion exitosa de turno de chat.
     *
     * @param response respuesta final devuelta al cliente
     * @param durationMs duracion del turno en milisegundos
     */
    public void recordChatTurnSuccess(ChatResponse response, long durationMs) {
        String ragUsed = boolTag(response != null && response.isRagUsed());
        String ragNeeded = boolTag(response != null && response.isRagNeeded());
        String reasoning = normalizeTag(response == null ? "" : response.getReasoningLevel(), "medium");

        meterRegistry.counter(
                NAME_CHAT_TURNS,
                "result", "success",
                "rag_used", ragUsed,
                "rag_needed", ragNeeded,
                "reasoning_level", reasoning
        ).increment();

        timer(
                NAME_CHAT_TURN_DURATION,
                "result", "success",
                "rag_used", ragUsed,
                "rag_needed", ragNeeded,
                "reasoning_level", reasoning
        ).record(Math.max(0L, durationMs), TimeUnit.MILLISECONDS);

        if (response != null) {
            meterRegistry.summary(NAME_CHAT_TURN_CONFIDENCE).record(clamp01(response.getConfidence()));
            meterRegistry.summary(NAME_CHAT_TURN_GROUNDED).record(Math.max(0, response.getGroundedSources()));
        }
    }

    /**
     * Registra una finalizacion con error de turno de chat.
     *
     * @param stage etapa donde se produjo el fallo
     * @param errorType tipo tecnico de error
     * @param durationMs duracion acumulada hasta el fallo
     */
    public void recordChatTurnFailure(String stage, String errorType, long durationMs) {
        String safeStage = normalizeTag(stage, "unknown");
        String safeErrorType = normalizeTag(errorType, "runtime_exception");
        meterRegistry.counter(
                NAME_CHAT_TURNS,
                "result", "failure",
                "rag_used", "false",
                "rag_needed", "false",
                "reasoning_level", "unknown"
        ).increment();
        meterRegistry.counter(
                NAME_CHAT_TURN_FAILURES,
                "stage", safeStage,
                "error_type", safeErrorType
        ).increment();
        timer(
                NAME_CHAT_TURN_DURATION,
                "result", "failure",
                "stage", safeStage
        ).record(Math.max(0L, durationMs), TimeUnit.MILLISECONDS);
    }

    /**
     * Registra encolado de turno en la cola de chat.
     *
     * @param hasMedia indica si el turno incluye adjuntos
     */
    public void recordQueueEnqueued(boolean hasMedia) {
        meterRegistry.counter(NAME_QUEUE_ENQUEUED, "has_media", boolTag(hasMedia)).increment();
    }

    /**
     * Registra una finalizacion correcta en cola.
     *
     * @param latencyMs latencia total en cola
     */
    public void recordQueueCompleted(long latencyMs) {
        meterRegistry.counter(NAME_QUEUE_COMPLETED).increment();
        timer(NAME_QUEUE_LATENCY, "result", "success")
                .record(Math.max(0L, latencyMs), TimeUnit.MILLISECONDS);
    }

    /**
     * Registra una finalizacion fallida en cola.
     *
     * @param errorType tipo tecnico de error
     * @param latencyMs latencia total hasta fallo
     */
    public void recordQueueFailed(String errorType, long latencyMs) {
        String safeErrorType = normalizeTag(errorType, "runtime_exception");
        meterRegistry.counter(NAME_QUEUE_FAILED, "error_type", safeErrorType).increment();
        timer(NAME_QUEUE_LATENCY, "result", "failure")
                .record(Math.max(0L, latencyMs), TimeUnit.MILLISECONDS);
    }

    /**
     * Actualiza gauges de cola activa/pendiente.
     *
     * @param activeSessions sesiones con procesamiento en curso
     * @param pendingMessages mensajes esperando turno
     */
    public void setChatQueueStats(int activeSessions, int pendingMessages) {
        queueActiveSessions.set(Math.max(0, activeSessions));
        queuePendingMessages.set(Math.max(0, pendingMessages));
    }

    /**
     * Publica perfil runtime calculado por el adaptador dinamico de chat.
     *
     * @param profile perfil/telemetria de adaptacion
     */
    public void recordRuntimeProfile(ChatRuntimeAdaptationService.RuntimeProfile profile) {
        if (profile == null) {
            return;
        }

        ChatRuntimeAdaptationService.RuntimeMode mode =
                profile.mode() == null ? ChatRuntimeAdaptationService.RuntimeMode.NORMAL : profile.mode();
        String nextModeTag = normalizeTag(mode.name(), "normal");
        String previousModeTag = normalizeTag(lastRuntimeMode.getAndSet(nextModeTag), "normal");
        if (!nextModeTag.equals(previousModeTag)) {
            meterRegistry.counter(
                    NAME_RUNTIME_MODE_TRANSITIONS,
                    "from", previousModeTag,
                    "to", nextModeTag
            ).increment();
        }

        runtimeModeCode.set(toModeCode(mode));
        runtimePreferFast.set(profile.preferFastModel() ? 1 : 0);

        ChatRuntimeAdaptationService.RuntimeSnapshot snapshot = profile.snapshot();
        if (snapshot == null) {
            return;
        }
        runtimePressureScore.set(Math.max(0, snapshot.pressureScore()));
        runtimeCpuLoad.set(clamp01(snapshot.cpuLoad()));
        runtimeMemoryLoad.set(clamp01(snapshot.memoryLoad()));
        runtimeGpuLoad.set(clamp01(snapshot.gpuLoad()));
        runtimeGpuMemoryLoad.set(clamp01(snapshot.gpuMemoryLoad()));
        runtimeNetworkUp.set(snapshot.networkUp() ? 1 : 0);
        runtimeNetworkLatencyMs.set(nonNegative(snapshot.networkLatencyMs()));
        runtimeModelLatencyMs.set(nonNegative(snapshot.avgModelLatencyMs()));
        runtimeTurnLatencyMs.set(nonNegative(snapshot.avgTurnLatencyMs()));
    }

    /**
     * Actualiza flags de estado global del robot RAG.
     *
     * @param paused indica pausa manual
     * @param running indica ejecucion actual
     * @param dryRun indica modo simulacion
     */
    public void setRagMaintenanceFlags(boolean paused, boolean running, boolean dryRun) {
        ragPaused.set(paused ? 1 : 0);
        ragRunning.set(running ? 1 : 0);
        ragDryRun.set(dryRun ? 1 : 0);
    }

    /**
     * Registra resultado de una corrida del mantenimiento RAG.
     *
     * @param run resumen de la corrida
     * @param durationMs duracion total en milisegundos
     */
    public void recordRagMaintenanceRun(RagMaintenanceRunDto run, long durationMs) {
        if (run == null) {
            return;
        }
        String trigger = normalizeTag(run.trigger(), "auto");
        String outcome = normalizeTag(run.outcome(), "idle");
        meterRegistry.counter(
                NAME_RAG_RUNS,
                "trigger", trigger,
                "outcome", outcome
        ).increment();
        timer(
                NAME_RAG_RUN_DURATION,
                "trigger", trigger,
                "outcome", outcome
        ).record(Math.max(0L, durationMs), TimeUnit.MILLISECONDS);

        ragLastScannedDocuments.set((double) Math.max(0L, run.scannedDocuments()));
        ragLastScannedChunks.set((double) Math.max(0L, run.scannedChunks()));
        ragLastRebuiltDocuments.set((double) Math.max(0L, run.rebuiltDocuments()));
        ragLastDeletedDocuments.set((double) Math.max(0L, run.deletedDocuments()));
        ragLastDeletedChunks.set((double) Math.max(0L, run.deletedChunks()));
        ragLastPrunedChunks.set((double) Math.max(0L, run.prunedChunks()));
        ragLastFreedBytes.set((double) Math.max(0L, run.estimatedBytesFreed()));
    }

    private void registerGauges() {
        Gauge.builder(NAME_QUEUE_ACTIVE, queueActiveSessions, AtomicInteger::get).register(meterRegistry);
        Gauge.builder(NAME_QUEUE_PENDING, queuePendingMessages, AtomicInteger::get).register(meterRegistry);

        Gauge.builder(NAME_RUNTIME_MODE_GAUGE, runtimeModeCode, AtomicInteger::get).register(meterRegistry);
        Gauge.builder(NAME_RUNTIME_PREFER_FAST_GAUGE, runtimePreferFast, AtomicInteger::get).register(meterRegistry);
        Gauge.builder(NAME_RUNTIME_PRESSURE_GAUGE, runtimePressureScore, AtomicInteger::get).register(meterRegistry);
        Gauge.builder(NAME_RUNTIME_CPU_GAUGE, runtimeCpuLoad, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_RUNTIME_MEMORY_GAUGE, runtimeMemoryLoad, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_RUNTIME_GPU_GAUGE, runtimeGpuLoad, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_RUNTIME_GPU_MEMORY_GAUGE, runtimeGpuMemoryLoad, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_RUNTIME_NETWORK_UP_GAUGE, runtimeNetworkUp, AtomicInteger::get).register(meterRegistry);
        Gauge.builder(NAME_RUNTIME_NETWORK_LATENCY_GAUGE, runtimeNetworkLatencyMs, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_RUNTIME_MODEL_LATENCY_GAUGE, runtimeModelLatencyMs, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_RUNTIME_TURN_LATENCY_GAUGE, runtimeTurnLatencyMs, AtomicReference::get).register(meterRegistry);

        Gauge.builder(NAME_RAG_RUNNING_GAUGE, ragRunning, AtomicInteger::get).register(meterRegistry);
        Gauge.builder(NAME_RAG_PAUSED_GAUGE, ragPaused, AtomicInteger::get).register(meterRegistry);
        Gauge.builder(NAME_RAG_DRY_RUN_GAUGE, ragDryRun, AtomicInteger::get).register(meterRegistry);
        Gauge.builder(NAME_RAG_LAST_SCANNED_DOCS_GAUGE, ragLastScannedDocuments, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_RAG_LAST_SCANNED_CHUNKS_GAUGE, ragLastScannedChunks, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_RAG_LAST_REBUILT_DOCS_GAUGE, ragLastRebuiltDocuments, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_RAG_LAST_DELETED_DOCS_GAUGE, ragLastDeletedDocuments, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_RAG_LAST_DELETED_CHUNKS_GAUGE, ragLastDeletedChunks, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_RAG_LAST_PRUNED_CHUNKS_GAUGE, ragLastPrunedChunks, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_RAG_LAST_FREED_BYTES_GAUGE, ragLastFreedBytes, AtomicReference::get).register(meterRegistry);
    }

    private Timer timer(String name, String... tags) {
        return meterRegistry.timer(name, tags);
    }

    private int toModeCode(ChatRuntimeAdaptationService.RuntimeMode mode) {
        if (mode == null) {
            return 0;
        }
        return switch (mode) {
            case NORMAL -> 0;
            case CONSTRAINED -> 1;
            case DEGRADED -> 2;
        };
    }

    private String boolTag(boolean value) {
        return value ? "true" : "false";
    }

    private double nonNegative(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            return 0.0;
        }
        return value;
    }

    private double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private String normalizeTag(String value, String fallback) {
        String source = (value == null || value.isBlank()) ? fallback : value.trim();
        String normalized = source
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_\\-\\.]", "_");
        return normalized.isBlank() ? fallback : normalized;
    }
}
