package com.example.apiasistente.monitoring.service;

import com.example.apiasistente.monitoring.dto.MonitoringAlertStateDto;
import com.example.apiasistente.monitoring.dto.ServerStatsDto;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publica metricas operativas periodicas para Prometheus, incluso sin trafico de chat.
 */
@Service
public class PrometheusMonitoringMetricsPublisher {

    private static final Logger log = LoggerFactory.getLogger(PrometheusMonitoringMetricsPublisher.class);

    private static final String NAME_CPU_SYSTEM_LOAD = "apiasistente.monitor.cpu.system.load.ratio";
    private static final String NAME_CPU_PROCESS_LOAD = "apiasistente.monitor.cpu.process.load.ratio";
    private static final String NAME_MEMORY_SYSTEM_USED = "apiasistente.monitor.memory.system.used.bytes";
    private static final String NAME_MEMORY_SYSTEM_TOTAL = "apiasistente.monitor.memory.system.total.bytes";
    private static final String NAME_MEMORY_JVM_USED = "apiasistente.monitor.memory.jvm.used.bytes";
    private static final String NAME_MEMORY_JVM_TOTAL = "apiasistente.monitor.memory.jvm.total.bytes";
    private static final String NAME_DISK_USED = "apiasistente.monitor.disk.used.bytes";
    private static final String NAME_DISK_TOTAL = "apiasistente.monitor.disk.total.bytes";
    private static final String NAME_SWAP_USED = "apiasistente.monitor.swap.used.bytes";
    private static final String NAME_SWAP_TOTAL = "apiasistente.monitor.swap.total.bytes";
    private static final String NAME_THREADS_TOTAL = "apiasistente.monitor.threads.total";
    private static final String NAME_THREADS_DAEMON = "apiasistente.monitor.threads.daemon";
    private static final String NAME_NETWORK_UP = "apiasistente.monitor.network.up";
    private static final String NAME_NETWORK_LATENCY = "apiasistente.monitor.network.latency.ms";
    private static final String NAME_GPU_AVAILABLE = "apiasistente.monitor.gpu.available";
    private static final String NAME_GPU_UTIL = "apiasistente.monitor.gpu.utilization.ratio";
    private static final String NAME_GPU_MEM_UTIL = "apiasistente.monitor.gpu.memory.utilization.ratio";
    private static final String NAME_GPU_MEM_USED_MB = "apiasistente.monitor.gpu.memory.used.mb";
    private static final String NAME_GPU_MEM_TOTAL_MB = "apiasistente.monitor.gpu.memory.total.mb";
    private static final String NAME_ALERT_CPU = "apiasistente.monitor.alert.cpu.high";
    private static final String NAME_ALERT_MEMORY = "apiasistente.monitor.alert.memory.high";
    private static final String NAME_ALERT_DISK = "apiasistente.monitor.alert.disk.high";
    private static final String NAME_ALERT_SWAP = "apiasistente.monitor.alert.swap.high";
    private static final String NAME_ALERT_INTERNET = "apiasistente.monitor.alert.internet.down";
    private static final String NAME_ALERT_TRANSITIONS = "apiasistente.monitor.alert.transitions";

    private final MonitorService monitorService;
    private final MonitoringAlertService alertService;
    private final MeterRegistry meterRegistry;

    private final AtomicReference<Double> cpuSystemLoad = new AtomicReference<>(0.0);
    private final AtomicReference<Double> cpuProcessLoad = new AtomicReference<>(0.0);
    private final AtomicReference<Double> memorySystemUsed = new AtomicReference<>(0.0);
    private final AtomicReference<Double> memorySystemTotal = new AtomicReference<>(0.0);
    private final AtomicReference<Double> memoryJvmUsed = new AtomicReference<>(0.0);
    private final AtomicReference<Double> memoryJvmTotal = new AtomicReference<>(0.0);
    private final AtomicReference<Double> diskUsed = new AtomicReference<>(0.0);
    private final AtomicReference<Double> diskTotal = new AtomicReference<>(0.0);
    private final AtomicReference<Double> swapUsed = new AtomicReference<>(0.0);
    private final AtomicReference<Double> swapTotal = new AtomicReference<>(0.0);
    private final AtomicReference<Double> threadsTotal = new AtomicReference<>(0.0);
    private final AtomicReference<Double> threadsDaemon = new AtomicReference<>(0.0);
    private final AtomicInteger networkUp = new AtomicInteger(1);
    private final AtomicReference<Double> networkLatencyMs = new AtomicReference<>(0.0);
    private final AtomicInteger gpuAvailable = new AtomicInteger(0);
    private final AtomicReference<Double> gpuUtil = new AtomicReference<>(0.0);
    private final AtomicReference<Double> gpuMemUtil = new AtomicReference<>(0.0);
    private final AtomicReference<Double> gpuMemUsedMb = new AtomicReference<>(0.0);
    private final AtomicReference<Double> gpuMemTotalMb = new AtomicReference<>(0.0);
    private final AtomicInteger alertCpuHigh = new AtomicInteger(0);
    private final AtomicInteger alertMemoryHigh = new AtomicInteger(0);
    private final AtomicInteger alertDiskHigh = new AtomicInteger(0);
    private final AtomicInteger alertSwapHigh = new AtomicInteger(0);
    private final AtomicInteger alertInternetDown = new AtomicInteger(0);

    private volatile AlertStateSnapshot previousAlertState;

    public PrometheusMonitoringMetricsPublisher(MonitorService monitorService,
                                                MonitoringAlertService alertService,
                                                MeterRegistry meterRegistry) {
        this.monitorService = monitorService;
        this.alertService = alertService;
        this.meterRegistry = meterRegistry;
        registerGauges();
    }

    @Scheduled(
            fixedDelayString = "${monitoring.prometheus-publish-interval-ms:10000}",
            initialDelayString = "${monitoring.prometheus-publish-initial-delay-ms:3000}"
    )
    public void publish() {
        try {
            ServerStatsDto stats = monitorService.snapshot();
            publishServerStats(stats);
        } catch (Exception ex) {
            log.debug("No se pudo publicar snapshot de monitor para Prometheus: {}", ex.getMessage());
        }

        try {
            MonitoringAlertStateDto state = alertService.currentState();
            publishAlertState(state);
        } catch (Exception ex) {
            log.debug("No se pudo publicar estado de alertas para Prometheus: {}", ex.getMessage());
        }
    }

    private void publishServerStats(ServerStatsDto stats) {
        if (stats == null) {
            return;
        }
        cpuSystemLoad.set(clamp01(stats.cpu() == null ? 0.0 : stats.cpu().loadSystem()));
        cpuProcessLoad.set(clamp01(stats.cpu() == null ? 0.0 : stats.cpu().loadProcess()));
        memorySystemUsed.set(nonNegative(stats.system() == null ? 0L : stats.system().usedBytes()));
        memorySystemTotal.set(nonNegative(stats.system() == null ? 0L : stats.system().totalBytes()));
        memoryJvmUsed.set(nonNegative(stats.jvm() == null ? 0L : stats.jvm().usedBytes()));
        memoryJvmTotal.set(nonNegative(stats.jvm() == null ? 0L : stats.jvm().totalBytes()));
        diskUsed.set(nonNegative(stats.disk() == null ? 0L : stats.disk().usedBytes()));
        diskTotal.set(nonNegative(stats.disk() == null ? 0L : stats.disk().totalBytes()));
        swapUsed.set(nonNegative(stats.swap() == null ? 0L : stats.swap().usedBytes()));
        swapTotal.set(nonNegative(stats.swap() == null ? 0L : stats.swap().totalBytes()));
        threadsTotal.set(nonNegative(stats.threads() == null ? 0 : stats.threads().count()));
        threadsDaemon.set(nonNegative(stats.threads() == null ? 0 : stats.threads().daemon()));

        networkUp.set(stats.network() != null && stats.network().internetUp() ? 1 : 0);
        networkLatencyMs.set(nonNegative(stats.network() == null ? 0L : stats.network().latencyMs()));

        boolean hasGpu = stats.gpu() != null && stats.gpu().available();
        gpuAvailable.set(hasGpu ? 1 : 0);
        gpuUtil.set(clamp01(hasGpu ? stats.gpu().utilization() : 0.0));
        gpuMemUtil.set(clamp01(hasGpu ? stats.gpu().memoryUtilization() : 0.0));
        gpuMemUsedMb.set(nonNegative(hasGpu ? stats.gpu().memoryUsedMb() : 0L));
        gpuMemTotalMb.set(nonNegative(hasGpu ? stats.gpu().memoryTotalMb() : 0L));
    }

    private void publishAlertState(MonitoringAlertStateDto state) {
        if (state == null) {
            return;
        }

        alertCpuHigh.set(state.cpuHigh() ? 1 : 0);
        alertMemoryHigh.set(state.memoryHigh() ? 1 : 0);
        alertDiskHigh.set(state.diskHigh() ? 1 : 0);
        alertSwapHigh.set(state.swapHigh() ? 1 : 0);
        alertInternetDown.set(state.internetDown() ? 1 : 0);

        AlertStateSnapshot next = new AlertStateSnapshot(
                state.cpuHigh(),
                state.memoryHigh(),
                state.diskHigh(),
                state.swapHigh(),
                state.internetDown()
        );
        AlertStateSnapshot prev = previousAlertState;
        previousAlertState = next;
        if (prev == null) {
            return;
        }

        countTransition("cpu", prev.cpuHigh(), next.cpuHigh());
        countTransition("memory", prev.memoryHigh(), next.memoryHigh());
        countTransition("disk", prev.diskHigh(), next.diskHigh());
        countTransition("swap", prev.swapHigh(), next.swapHigh());
        countTransition("internet", prev.internetDown(), next.internetDown());
    }

    private void countTransition(String alert, boolean previous, boolean next) {
        if (previous == next) {
            return;
        }
        meterRegistry.counter(
                NAME_ALERT_TRANSITIONS,
                "alert", alert,
                "state", next ? "on" : "off"
        ).increment();
    }

    private void registerGauges() {
        Gauge.builder(NAME_CPU_SYSTEM_LOAD, cpuSystemLoad, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_CPU_PROCESS_LOAD, cpuProcessLoad, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_MEMORY_SYSTEM_USED, memorySystemUsed, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_MEMORY_SYSTEM_TOTAL, memorySystemTotal, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_MEMORY_JVM_USED, memoryJvmUsed, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_MEMORY_JVM_TOTAL, memoryJvmTotal, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_DISK_USED, diskUsed, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_DISK_TOTAL, diskTotal, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_SWAP_USED, swapUsed, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_SWAP_TOTAL, swapTotal, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_THREADS_TOTAL, threadsTotal, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_THREADS_DAEMON, threadsDaemon, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_NETWORK_UP, networkUp, AtomicInteger::get).register(meterRegistry);
        Gauge.builder(NAME_NETWORK_LATENCY, networkLatencyMs, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_GPU_AVAILABLE, gpuAvailable, AtomicInteger::get).register(meterRegistry);
        Gauge.builder(NAME_GPU_UTIL, gpuUtil, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_GPU_MEM_UTIL, gpuMemUtil, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_GPU_MEM_USED_MB, gpuMemUsedMb, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_GPU_MEM_TOTAL_MB, gpuMemTotalMb, AtomicReference::get).register(meterRegistry);
        Gauge.builder(NAME_ALERT_CPU, alertCpuHigh, AtomicInteger::get).register(meterRegistry);
        Gauge.builder(NAME_ALERT_MEMORY, alertMemoryHigh, AtomicInteger::get).register(meterRegistry);
        Gauge.builder(NAME_ALERT_DISK, alertDiskHigh, AtomicInteger::get).register(meterRegistry);
        Gauge.builder(NAME_ALERT_SWAP, alertSwapHigh, AtomicInteger::get).register(meterRegistry);
        Gauge.builder(NAME_ALERT_INTERNET, alertInternetDown, AtomicInteger::get).register(meterRegistry);
    }

    private double nonNegative(long value) {
        return Math.max(0.0, (double) value);
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

    private record AlertStateSnapshot(boolean cpuHigh,
                                      boolean memoryHigh,
                                      boolean diskHigh,
                                      boolean swapHigh,
                                      boolean internetDown) {
    }
}
