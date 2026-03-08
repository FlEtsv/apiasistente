package com.example.apiasistente.monitoring.service;

import com.example.apiasistente.monitoring.dto.ServerStatsDto;
import com.sun.management.OperatingSystemMXBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.net.URI;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Servicio para Monitor.
 */
@Service
public class MonitorService {

    private static final long NETWORK_CACHE_MS = 5_000;
    private static final long GPU_CACHE_MS = 5_000;

    @Value("${monitoring.internet-check-url:https://www.gstatic.com/generate_204}")
    private String internetCheckUrl;

    @Value("${monitoring.internet-check-timeout-ms:2000}")
    private int internetCheckTimeoutMs;

    @Value("${monitoring.gpu.enabled:true}")
    private boolean gpuEnabled;

    @Value("${monitoring.gpu.command:nvidia-smi --query-gpu=utilization.gpu,memory.used,memory.total,name --format=csv,noheader,nounits}")
    private String gpuCommand;

    @Value("${monitoring.gpu.timeout-ms:900}")
    private long gpuTimeoutMs;

    private volatile ServerStatsDto.NetworkInfo lastNetwork;
    private volatile long lastNetworkCheckMs;
    private volatile ServerStatsDto.GpuInfo lastGpu;
    private volatile long lastGpuCheckMs;

    public ServerStatsDto snapshot() {
        String host = resolveHostname();
        Instant ts = Instant.now();
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;

        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        double loadSystem = safe(osBean.getCpuLoad());
        double loadProcess = safe(osBean.getProcessCpuLoad());
        double loadAverage = safe(osBean.getSystemLoadAverage());

        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memBean.getNonHeapMemoryUsage();

        long jvmUsed = safeAdd(heap.getUsed(), nonHeap.getUsed());
        long jvmTotal = safeAdd(heap.getCommitted(), nonHeap.getCommitted());

        long jvmHeapTotal = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        long jvmNonHeapTotal = nonHeap.getCommitted();

        long sysTotal = osBean.getTotalMemorySize();
        long sysFree = osBean.getFreeMemorySize();
        long sysUsed = safeDiff(sysTotal, sysFree);

        long swapTotal = osBean.getTotalSwapSpaceSize();
        long swapFree = osBean.getFreeSwapSpaceSize();
        long swapUsed = safeDiff(swapTotal, swapFree);

        ServerStatsDto.DiskInfo disk = diskInfo();

        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        ServerStatsDto.ThreadInfo threads = new ServerStatsDto.ThreadInfo(
                threadBean.getThreadCount(),
                threadBean.getPeakThreadCount(),
                threadBean.getDaemonThreadCount()
        );

        ServerStatsDto.GcInfo gc = gcInfo();
        ServerStatsDto.NetworkInfo network = networkInfo();
        ServerStatsDto.GpuInfo gpu = gpuInfo();

        return new ServerStatsDto(
                host,
                ts,
                uptimeSeconds,
                new ServerStatsDto.CpuInfo(loadSystem, loadProcess, loadAverage),
                new ServerStatsDto.MemoryInfo(jvmUsed, jvmTotal),
                new ServerStatsDto.MemoryInfo(heap.getUsed(), jvmHeapTotal),
                new ServerStatsDto.MemoryInfo(nonHeap.getUsed(), jvmNonHeapTotal),
                new ServerStatsDto.MemoryInfo(sysUsed, sysTotal),
                new ServerStatsDto.MemoryInfo(swapUsed, swapTotal),
                disk,
                threads,
                gc,
                network,
                gpu,
                osBean.getAvailableProcessors()
        );
    }

    private ServerStatsDto.DiskInfo diskInfo() {
        File[] roots = File.listRoots();
        long total = 0L;
        long free = 0L;
        if (roots != null) {
            for (File root : roots) {
                total = safeAdd(total, root.getTotalSpace());
                free = safeAdd(free, root.getFreeSpace());
            }
        }
        long used = safeDiff(total, free);
        return new ServerStatsDto.DiskInfo(used, total);
    }

    private ServerStatsDto.GcInfo gcInfo() {
        long count = 0L;
        long timeMs = 0L;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = gcBean.getCollectionCount();
            long t = gcBean.getCollectionTime();
            if (c > 0) count += c;
            if (t > 0) timeMs += t;
        }
        return new ServerStatsDto.GcInfo(count, timeMs);
    }

    private ServerStatsDto.NetworkInfo networkInfo() {
        long now = System.currentTimeMillis();
        ServerStatsDto.NetworkInfo cached = lastNetwork;
        if (cached != null && (now - lastNetworkCheckMs) < NETWORK_CACHE_MS) {
            return cached;
        }
        ServerStatsDto.NetworkInfo fresh = probeInternet();
        lastNetwork = fresh;
        lastNetworkCheckMs = now;
        return fresh;
    }

    private ServerStatsDto.NetworkInfo probeInternet() {
        String url = (internetCheckUrl == null || internetCheckUrl.isBlank())
                ? "https://www.gstatic.com/generate_204"
                : internetCheckUrl.trim();

        long start = System.nanoTime();
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(internetCheckTimeoutMs);
            conn.setReadTimeout(internetCheckTimeoutMs);
            conn.connect();
            int status = conn.getResponseCode();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            boolean up = status >= 200 && status < 400;
            return new ServerStatsDto.NetworkInfo(up, elapsedMs, url);
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            return new ServerStatsDto.NetworkInfo(false, elapsedMs, url);
        }
    }

    private ServerStatsDto.GpuInfo gpuInfo() {
        if (!gpuEnabled) {
            return GpuInfoUnavailable.INSTANCE;
        }
        long now = System.currentTimeMillis();
        ServerStatsDto.GpuInfo cached = lastGpu;
        if (cached != null && (now - lastGpuCheckMs) < GPU_CACHE_MS) {
            return cached;
        }
        ServerStatsDto.GpuInfo fresh = probeGpu();
        lastGpu = fresh;
        lastGpuCheckMs = now;
        return fresh;
    }

    private ServerStatsDto.GpuInfo probeGpu() {
        String command = gpuCommand == null ? "" : gpuCommand.trim();
        if (command.isBlank()) {
            return GpuInfoUnavailable.INSTANCE;
        }

        try {
            ProcessBuilder pb = isWindows()
                    ? new ProcessBuilder("cmd", "/c", command)
                    : new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(Math.max(100L, gpuTimeoutMs), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return GpuInfoUnavailable.INSTANCE;
            }
            if (process.exitValue() != 0) {
                return GpuInfoUnavailable.INSTANCE;
            }

            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            return parseGpuCsv(output);
        } catch (Exception e) {
            return GpuInfoUnavailable.INSTANCE;
        }
    }

    private ServerStatsDto.GpuInfo parseGpuCsv(String output) {
        if (output == null || output.isBlank()) {
            return GpuInfoUnavailable.INSTANCE;
        }

        String[] rows = output.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        double maxGpuUtil = 0.0;
        long totalMemUsedMb = 0L;
        long totalMemMb = 0L;
        String firstName = "";
        int parsedRows = 0;

        for (String row : rows) {
            if (row == null || row.isBlank()) {
                continue;
            }
            String[] parts = row.split(",", 4);
            if (parts.length < 3) {
                continue;
            }

            Integer utilPct = parseInt(parts[0]);
            Long memUsed = parseLong(parts[1]);
            Long memTotal = parseLong(parts[2]);
            String name = parts.length >= 4 ? parts[3].trim() : "";

            if (utilPct == null || memUsed == null || memTotal == null || memTotal <= 0) {
                continue;
            }
            if (firstName.isBlank() && !name.isBlank()) {
                firstName = name;
            }

            parsedRows++;
            maxGpuUtil = Math.max(maxGpuUtil, clamp01(utilPct / 100.0));
            totalMemUsedMb += Math.max(0L, memUsed);
            totalMemMb += Math.max(0L, memTotal);
        }

        if (parsedRows == 0 || totalMemMb <= 0L) {
            return GpuInfoUnavailable.INSTANCE;
        }

        double memoryUtil = clamp01((double) totalMemUsedMb / (double) totalMemMb);
        return new ServerStatsDto.GpuInfo(
                true,
                maxGpuUtil,
                memoryUtil,
                totalMemUsedMb,
                totalMemMb,
                firstName
        );
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    private String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private double safe(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
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

    private Integer parseInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String clean = raw.replaceAll("[^0-9-]", "");
        if (clean.isBlank() || "-".equals(clean)) {
            return null;
        }
        try {
            return Integer.parseInt(clean);
        } catch (Exception e) {
            return null;
        }
    }

    private Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String clean = raw.replaceAll("[^0-9-]", "");
        if (clean.isBlank() || "-".equals(clean)) {
            return null;
        }
        try {
            return Long.parseLong(clean);
        } catch (Exception e) {
            return null;
        }
    }

    private long safeAdd(long a, long b) {
        long res = a + b;
        if (res < 0) return Long.MAX_VALUE;
        return res;
    }

    private long safeDiff(long total, long free) {
        long used = total - free;
        return Math.max(0L, used);
    }

    /**
     * Valor singleton para representar ausencia de GPU sin generar objetos nuevos en cada snapshot.
     */
    private static final class GpuInfoUnavailable {
        private static final ServerStatsDto.GpuInfo INSTANCE = new ServerStatsDto.GpuInfo(
                false,
                0.0,
                0.0,
                0L,
                0L,
                ""
        );
    }
}


