package com.example.apiasistente.monitoring.dto;

import java.time.Instant;

/**
 * Snapshot consolidado de estado de host y JVM.
 *
 * @param hostname nombre de host local
 * @param timestamp instante de captura
 * @param uptimeSeconds uptime del proceso en segundos
 * @param cpu metricas de CPU
 * @param jvm memoria JVM total
 * @param jvmHeap memoria heap JVM
 * @param jvmNonHeap memoria non-heap JVM
 * @param system memoria del sistema operativo
 * @param swap uso de swap
 * @param disk uso de disco
 * @param threads metricas de hilos
 * @param gc estadisticas de GC
 * @param network estado de conectividad externa
 * @param gpu telemetria de GPU
 * @param availableProcessors procesadores disponibles para la JVM
 */
public record ServerStatsDto(
        String hostname,
        Instant timestamp,
        long uptimeSeconds,
        CpuInfo cpu,
        MemoryInfo jvm,
        MemoryInfo jvmHeap,
        MemoryInfo jvmNonHeap,
        MemoryInfo system,
        MemoryInfo swap,
        DiskInfo disk,
        ThreadInfo threads,
        GcInfo gc,
        NetworkInfo network,
        GpuInfo gpu,
        int availableProcessors
) {
    public record CpuInfo(double loadSystem, double loadProcess, double loadAverage) {}

    public record MemoryInfo(long usedBytes, long totalBytes) {}

    public record DiskInfo(long usedBytes, long totalBytes) {}

    public record ThreadInfo(int count, int peak, int daemon) {}

    public record GcInfo(long totalCount, long totalTimeMs) {}

    public record NetworkInfo(boolean internetUp, long latencyMs, String checkedUrl) {}

    public record GpuInfo(boolean available,
                          double utilization,
                          double memoryUtilization,
                          long memoryUsedMb,
                          long memoryTotalMb,
                          String name) {
    }
}

