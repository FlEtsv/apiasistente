package com.example.apiasistente.chat.service;

import com.example.apiasistente.chat.config.ChatRuntimeAdaptationProperties;
import com.example.apiasistente.monitoring.dto.ServerStatsDto;
import com.example.apiasistente.monitoring.service.AppMetricsService;
import com.example.apiasistente.monitoring.service.MonitorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ajusta parametros de inferencia y preferencia de modelo en funcion del estado local.
 */
@Service
public class ChatRuntimeAdaptationService {

    private final MonitorService monitorService;
    private final ChatRuntimeAdaptationProperties properties;

    private final AtomicReference<CachedProfile> cachedProfile = new AtomicReference<>();
    private final Object latencyLock = new Object();
    private AppMetricsService metricsService;

    private double modelLatencyEmaMs = -1.0;
    private long modelLatencySamples = 0L;
    private double turnLatencyEmaMs = -1.0;
    private long turnLatencySamples = 0L;

    public ChatRuntimeAdaptationService(MonitorService monitorService,
                                        ChatRuntimeAdaptationProperties properties) {
        this.monitorService = monitorService;
        this.properties = properties;
    }

    @Autowired(required = false)
    void setMetricsService(AppMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    /**
     * Registra latencia de una llamada al modelo para ajustar el perfil de carga.
     */
    public void recordModelLatency(long latencyMs) {
        updateLatency(latencyMs, true);
    }

    /**
     * Registra latencia total de un turno para ajustar el perfil de carga.
     */
    public void recordTurnLatency(long latencyMs) {
        updateLatency(latencyMs, false);
    }

    /**
     * Devuelve el perfil vigente con cache corta para evitar sobrecoste en cada llamada.
     */
    public RuntimeProfile currentProfile() {
        if (!properties.isEnabled()) {
            return disabledProfile();
        }

        long now = System.currentTimeMillis();
        CachedProfile cached = cachedProfile.get();
        if (cached != null && now - cached.generatedAtMs() < Math.max(250L, properties.getProfileTtlMs())) {
            return cached.profile();
        }

        RuntimeProfile fresh = computeProfile();
        if (metricsService != null) {
            metricsService.recordRuntimeProfile(fresh);
        }
        cachedProfile.set(new CachedProfile(now, fresh));
        return fresh;
    }

    private RuntimeProfile computeProfile() {
        ServerStatsDto stats = monitorService.snapshot();
        LatencyWindow latencies = latencyWindow();

        double cpuLoad = clamp01(Math.max(stats.cpu().loadSystem(), stats.cpu().loadProcess()));
        double memoryLoad = ratio(stats.system().usedBytes(), stats.system().totalBytes());

        ServerStatsDto.GpuInfo gpu = stats.gpu();
        boolean gpuAvailable = gpu != null && gpu.available();
        double gpuLoad = gpuAvailable ? clamp01(gpu.utilization()) : 0.0;
        double gpuMemoryLoad = gpuAvailable ? clamp01(gpu.memoryUtilization()) : 0.0;

        boolean networkUp = stats.network() != null && stats.network().internetUp();
        long networkLatencyMs = stats.network() == null ? 0L : Math.max(0L, stats.network().latencyMs());

        boolean cpuHigh = cpuLoad >= clamp01(properties.getCpuThreshold());
        boolean memoryHigh = memoryLoad >= clamp01(properties.getMemoryThreshold());
        boolean gpuHigh = gpuAvailable
                && Math.max(gpuLoad, gpuMemoryLoad) >= clamp01(properties.getGpuThreshold());
        boolean networkHigh = !networkUp || networkLatencyMs >= Math.max(1L, properties.getNetworkLatencyThresholdMs());
        boolean modelSlow = latencies.modelEmaMs() > 0
                && latencies.modelEmaMs() >= Math.max(1L, properties.getModelLatencyThresholdMs());
        boolean turnSlow = latencies.turnEmaMs() > 0
                && latencies.turnEmaMs() >= Math.max(1L, properties.getTurnLatencyThresholdMs());

        int pressureScore = countTrue(cpuHigh, memoryHigh, gpuHigh, networkHigh, modelSlow, turnSlow);
        RuntimeMode mode = resolveMode(networkUp, pressureScore, cpuHigh, memoryHigh, gpuHigh, turnSlow);

        RuntimeSnapshot snapshot = new RuntimeSnapshot(
                Instant.now(),
                cpuLoad,
                memoryLoad,
                networkUp,
                networkLatencyMs,
                gpuAvailable,
                gpuLoad,
                gpuMemoryLoad,
                round2(latencies.modelEmaMs()),
                latencies.modelSamples(),
                round2(latencies.turnEmaMs()),
                latencies.turnSamples(),
                pressureScore
        );

        return new RuntimeProfile(
                mode,
                shouldPreferFast(mode),
                resolveTemperatureOverride(mode),
                resolveMaxTokensOverride(mode),
                buildReason(cpuHigh, memoryHigh, gpuHigh, networkHigh, modelSlow, turnSlow),
                snapshot
        );
    }

    private RuntimeProfile disabledProfile() {
        return new RuntimeProfile(
                RuntimeMode.NORMAL,
                false,
                null,
                null,
                "runtime-adaptation-disabled",
                RuntimeSnapshot.empty()
        );
    }

    private void updateLatency(long latencyMs, boolean modelLatency) {
        if (latencyMs <= 0L) {
            return;
        }
        double alpha = clampAlpha(properties.getEmaAlpha());
        synchronized (latencyLock) {
            if (modelLatency) {
                modelLatencyEmaMs = modelLatencyEmaMs < 0.0
                        ? latencyMs
                        : (alpha * latencyMs) + ((1.0 - alpha) * modelLatencyEmaMs);
                modelLatencySamples++;
            } else {
                turnLatencyEmaMs = turnLatencyEmaMs < 0.0
                        ? latencyMs
                        : (alpha * latencyMs) + ((1.0 - alpha) * turnLatencyEmaMs);
                turnLatencySamples++;
            }
        }
        cachedProfile.set(null);
    }

    private LatencyWindow latencyWindow() {
        synchronized (latencyLock) {
            return new LatencyWindow(
                    modelLatencyEmaMs < 0.0 ? 0.0 : modelLatencyEmaMs,
                    modelLatencySamples,
                    turnLatencyEmaMs < 0.0 ? 0.0 : turnLatencyEmaMs,
                    turnLatencySamples
            );
        }
    }

    private RuntimeMode resolveMode(boolean networkUp,
                                    int pressureScore,
                                    boolean cpuHigh,
                                    boolean memoryHigh,
                                    boolean gpuHigh,
                                    boolean turnSlow) {
        if (!networkUp) {
            return RuntimeMode.DEGRADED;
        }
        if (pressureScore >= 3) {
            return RuntimeMode.DEGRADED;
        }
        if (turnSlow && (cpuHigh || memoryHigh || gpuHigh)) {
            return RuntimeMode.DEGRADED;
        }
        if (pressureScore >= 1) {
            return RuntimeMode.CONSTRAINED;
        }
        return RuntimeMode.NORMAL;
    }

    private boolean shouldPreferFast(RuntimeMode mode) {
        return switch (mode) {
            case NORMAL -> false;
            case CONSTRAINED -> properties.isForceFastWhenConstrained();
            case DEGRADED -> properties.isForceFastWhenDegraded();
        };
    }

    private Double resolveTemperatureOverride(RuntimeMode mode) {
        return switch (mode) {
            case NORMAL -> null;
            case CONSTRAINED -> clamp01(properties.getConstrainedTemperature());
            case DEGRADED -> clamp01(properties.getDegradedTemperature());
        };
    }

    private Integer resolveMaxTokensOverride(RuntimeMode mode) {
        int configured = switch (mode) {
            case NORMAL -> properties.getNormalMaxTokens();
            case CONSTRAINED -> firstPositive(properties.getConstrainedMaxTokens(), properties.getNormalMaxTokens());
            case DEGRADED -> firstPositive(
                    properties.getDegradedMaxTokens(),
                    properties.getConstrainedMaxTokens(),
                    properties.getNormalMaxTokens()
            );
        };
        return configured > 0 ? configured : null;
    }

    private String buildReason(boolean cpuHigh,
                               boolean memoryHigh,
                               boolean gpuHigh,
                               boolean networkHigh,
                               boolean modelSlow,
                               boolean turnSlow) {
        List<String> reasons = new ArrayList<>();
        if (cpuHigh) reasons.add("cpu-high");
        if (memoryHigh) reasons.add("memory-high");
        if (gpuHigh) reasons.add("gpu-high");
        if (networkHigh) reasons.add("network-high");
        if (modelSlow) reasons.add("model-latency-high");
        if (turnSlow) reasons.add("turn-latency-high");
        if (reasons.isEmpty()) {
            return "steady";
        }
        return String.join(",", reasons);
    }

    private int firstPositive(int... values) {
        if (values == null) {
            return 0;
        }
        for (int value : values) {
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    private int countTrue(boolean... values) {
        if (values == null || values.length == 0) {
            return 0;
        }
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private double ratio(long used, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return clamp01((double) used / (double) total);
    }

    private double clampAlpha(double value) {
        if (!Double.isFinite(value)) {
            return 0.25;
        }
        if (value < 0.05) {
            return 0.05;
        }
        if (value > 1.0) {
            return 1.0;
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

    private double round2(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.round(value * 100.0) / 100.0;
    }

    private record CachedProfile(long generatedAtMs, RuntimeProfile profile) {
    }

    private record LatencyWindow(double modelEmaMs, long modelSamples, double turnEmaMs, long turnSamples) {
    }

    public enum RuntimeMode {
        NORMAL,
        CONSTRAINED,
        DEGRADED
    }

    public record RuntimeProfile(RuntimeMode mode,
                                 boolean preferFastModel,
                                 Double temperatureOverride,
                                 Integer maxTokensOverride,
                                 String reason,
                                 RuntimeSnapshot snapshot) {

        public RuntimeProfile {
            mode = mode == null ? RuntimeMode.NORMAL : mode;
            reason = reason == null ? "" : reason.trim();
            snapshot = snapshot == null ? RuntimeSnapshot.empty() : snapshot;
        }
    }

    public record RuntimeSnapshot(Instant sampledAt,
                                  double cpuLoad,
                                  double memoryLoad,
                                  boolean networkUp,
                                  long networkLatencyMs,
                                  boolean gpuAvailable,
                                  double gpuLoad,
                                  double gpuMemoryLoad,
                                  double avgModelLatencyMs,
                                  long modelLatencySamples,
                                  double avgTurnLatencyMs,
                                  long turnLatencySamples,
                                  int pressureScore) {

        public RuntimeSnapshot {
            sampledAt = sampledAt == null ? Instant.now() : sampledAt;
            cpuLoad = sanitize01(cpuLoad);
            memoryLoad = sanitize01(memoryLoad);
            networkLatencyMs = Math.max(0L, networkLatencyMs);
            gpuLoad = sanitize01(gpuLoad);
            gpuMemoryLoad = sanitize01(gpuMemoryLoad);
            avgModelLatencyMs = sanitizeMs(avgModelLatencyMs);
            modelLatencySamples = Math.max(0L, modelLatencySamples);
            avgTurnLatencyMs = sanitizeMs(avgTurnLatencyMs);
            turnLatencySamples = Math.max(0L, turnLatencySamples);
            pressureScore = Math.max(0, pressureScore);
        }

        public static RuntimeSnapshot empty() {
            return new RuntimeSnapshot(
                    Instant.now(),
                    0.0,
                    0.0,
                    true,
                    0L,
                    false,
                    0.0,
                    0.0,
                    0.0,
                    0L,
                    0.0,
                    0L,
                    0
            );
        }

        private static double sanitize01(double value) {
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

        private static double sanitizeMs(double value) {
            if (!Double.isFinite(value) || value < 0.0) {
                return 0.0;
            }
            return value;
        }
    }
}
