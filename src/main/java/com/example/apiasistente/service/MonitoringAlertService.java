package com.example.apiasistente.service;

import com.example.apiasistente.model.dto.MonitoringAlertDto;
import com.example.apiasistente.model.dto.MonitoringAlertStateDto;
import com.example.apiasistente.model.dto.ServerStatsDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class MonitoringAlertService {

    private final MonitorService monitorService;
    private final MonitoringAlertStore alertStore;

    private final boolean enabled;
    private final double cpuThreshold;
    private final double memoryThreshold;
    private final double diskThreshold;
    private final double swapThreshold;
    private final long cooldownMs;

    private final AtomicReference<AlertState> state = new AtomicReference<>(new AlertState());

    public MonitoringAlertService(
            MonitorService monitorService,
            MonitoringAlertStore alertStore,
            @Value("${monitoring.alerts.enabled:true}") boolean enabled,
            @Value("${monitoring.alerts.cpu-threshold:0.90}") double cpuThreshold,
            @Value("${monitoring.alerts.memory-threshold:0.90}") double memoryThreshold,
            @Value("${monitoring.alerts.disk-threshold:0.90}") double diskThreshold,
            @Value("${monitoring.alerts.swap-threshold:0.90}") double swapThreshold,
            @Value("${monitoring.alerts.cooldown-ms:300000}") long cooldownMs
    ) {
        this.monitorService = monitorService;
        this.alertStore = alertStore;
        this.enabled = enabled;
        this.cpuThreshold = cpuThreshold;
        this.memoryThreshold = memoryThreshold;
        this.diskThreshold = diskThreshold;
        this.swapThreshold = swapThreshold;
        this.cooldownMs = cooldownMs;
    }

    @Scheduled(fixedDelayString = "${monitoring.alerts.check-interval-ms:15000}")
    public void check() {
        if (!enabled) return;

        ServerStatsDto stats = monitorService.snapshot();

        double cpu = stats.cpu().loadSystem();
        double mem = ratio(stats.system().usedBytes(), stats.system().totalBytes());
        double disk = ratio(stats.disk().usedBytes(), stats.disk().totalBytes());
        double swap = ratio(stats.swap().usedBytes(), stats.swap().totalBytes());
        boolean internetUp = stats.network().internetUp();

        AlertState prev = state.get();
        AlertState next = prev.copy();

        long now = System.currentTimeMillis();

        next.cpuHigh = evaluate("CPU", cpu >= cpuThreshold, prev.cpuHigh, now, prev.cpuLast, () ->
                recordAlert("CPU", "CPU ALTO", stats, cpu, cpuThreshold),
                () -> recordRecover("CPU", "CPU NORMAL", stats, cpu));
        if (next.cpuHigh != prev.cpuHigh) next.cpuLast = now;

        next.memHigh = evaluate("MEM", mem >= memoryThreshold, prev.memHigh, now, prev.memLast, () ->
                recordAlert("MEM", "MEMORIA ALTA", stats, mem, memoryThreshold),
                () -> recordRecover("MEM", "MEMORIA NORMAL", stats, mem));
        if (next.memHigh != prev.memHigh) next.memLast = now;

        next.diskHigh = evaluate("DISK", disk >= diskThreshold, prev.diskHigh, now, prev.diskLast, () ->
                recordAlert("DISK", "DISCO CRITICO", stats, disk, diskThreshold),
                () -> recordRecover("DISK", "DISCO NORMAL", stats, disk));
        if (next.diskHigh != prev.diskHigh) next.diskLast = now;

        next.swapHigh = evaluate("SWAP", swap >= swapThreshold, prev.swapHigh, now, prev.swapLast, () ->
                recordAlert("SWAP", "SWAP ALTA", stats, swap, swapThreshold),
                () -> recordRecover("SWAP", "SWAP NORMAL", stats, swap));
        if (next.swapHigh != prev.swapHigh) next.swapLast = now;

        next.internetDown = evaluate("NET", !internetUp, prev.internetDown, now, prev.netLast, () ->
                recordInternetAlert(stats),
                () -> recordInternetRecover(stats));
        if (next.internetDown != prev.internetDown) next.netLast = now;

        state.set(next);
    }

    private boolean evaluate(String key,
                             boolean condition,
                             boolean prevState,
                             long now,
                             long lastSent,
                             Runnable onTrigger,
                             Runnable onRecover) {
        if (condition && !prevState && allowSend(now, lastSent)) {
            onTrigger.run();
            return true;
        }
        if (!condition && prevState && allowSend(now, lastSent)) {
            onRecover.run();
            return false;
        }
        return prevState;
    }

    private boolean allowSend(long now, long lastSent) {
        return now - lastSent >= cooldownMs;
    }

    public MonitoringAlertStateDto currentState() {
        AlertState s = state.get();
        return new MonitoringAlertStateDto(
                s.cpuHigh,
                s.memHigh,
                s.diskHigh,
                s.swapHigh,
                s.internetDown,
                toInstant(s.cpuLast),
                toInstant(s.memLast),
                toInstant(s.diskLast),
                toInstant(s.swapLast),
                toInstant(s.netLast)
        );
    }

    private void recordAlert(String key, String title, ServerStatsDto stats, double value, double threshold) {
        Instant now = Instant.now();
        String msg = String.format(
                "ALERTA: %s%nHost: %s%nValor: %s (umbral %.0f%%)%nHora: %s",
                title,
                stats.hostname(),
                pct(value),
                threshold * 100.0,
                now
        );
        recordEvent("ALERT", key, title, msg, stats, value, threshold, null, null, now);
    }

    private void recordRecover(String key, String title, ServerStatsDto stats, double value) {
        Instant now = Instant.now();
        String msg = String.format(
                "RECUPERADO: %s%nHost: %s%nValor: %s%nHora: %s",
                title,
                stats.hostname(),
                pct(value),
                now
        );
        recordEvent("RECOVER", key, title, msg, stats, value, null, null, null, now);
    }

    private void recordInternetAlert(ServerStatsDto stats) {
        Instant now = Instant.now();
        String msg = String.format(
                "ALERTA: INTERNET CAIDO%nHost: %s%nURL: %s%nLatencia: %d ms%nHora: %s",
                stats.hostname(),
                stats.network().checkedUrl(),
                stats.network().latencyMs(),
                now
        );
        recordEvent("ALERT", "INTERNET", "INTERNET CAIDO", msg, stats, null, null, stats.network().latencyMs(), stats.network().checkedUrl(), now);
    }

    private void recordInternetRecover(ServerStatsDto stats) {
        Instant now = Instant.now();
        String msg = String.format(
                "RECUPERADO: INTERNET OK%nHost: %s%nURL: %s%nLatencia: %d ms%nHora: %s",
                stats.hostname(),
                stats.network().checkedUrl(),
                stats.network().latencyMs(),
                now
        );
        recordEvent("RECOVER", "INTERNET", "INTERNET OK", msg, stats, null, null, stats.network().latencyMs(), stats.network().checkedUrl(), now);
    }

    private double ratio(long used, long total) {
        if (total <= 0) return 0.0;
        return Math.min(1.0, Math.max(0.0, (double) used / (double) total));
    }

    private String pct(double v) {
        return String.format(java.util.Locale.US, "%.1f%%", v * 100.0);
    }

    private Instant toInstant(long epochMs) {
        if (epochMs <= 0) return null;
        return Instant.ofEpochMilli(epochMs);
    }

    private void recordEvent(String level,
                             String key,
                             String title,
                             String message,
                             ServerStatsDto stats,
                             Double value,
                             Double threshold,
                             Long latencyMs,
                             String checkedUrl,
                             Instant timestamp) {
        String host = stats == null ? "unknown" : stats.hostname();
        MonitoringAlertDto event = new MonitoringAlertDto(
                UUID.randomUUID().toString(),
                timestamp == null ? Instant.now() : timestamp,
                level,
                key,
                title,
                message,
                host,
                value,
                threshold,
                latencyMs,
                checkedUrl
        );
        alertStore.record(event);
    }

    private static final class AlertState {
        boolean cpuHigh;
        boolean memHigh;
        boolean diskHigh;
        boolean swapHigh;
        boolean internetDown;

        long cpuLast;
        long memLast;
        long diskLast;
        long swapLast;
        long netLast;

        AlertState copy() {
            AlertState s = new AlertState();
            s.cpuHigh = cpuHigh;
            s.memHigh = memHigh;
            s.diskHigh = diskHigh;
            s.swapHigh = swapHigh;
            s.internetDown = internetDown;
            s.cpuLast = cpuLast;
            s.memLast = memLast;
            s.diskLast = diskLast;
            s.swapLast = swapLast;
            s.netLast = netLast;
            return s;
        }
    }
}
