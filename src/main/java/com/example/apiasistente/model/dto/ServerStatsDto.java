package com.example.apiasistente.model.dto;

import java.time.Instant;

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
        int availableProcessors
) {
    public record CpuInfo(double loadSystem, double loadProcess, double loadAverage) {}

    public record MemoryInfo(long usedBytes, long totalBytes) {}

    public record DiskInfo(long usedBytes, long totalBytes) {}

    public record ThreadInfo(int count, int peak, int daemon) {}

    public record GcInfo(long totalCount, long totalTimeMs) {}

    public record NetworkInfo(boolean internetUp, long latencyMs, String checkedUrl) {}
}
