package com.example.apiasistente.service;

import com.example.apiasistente.model.dto.ServerStatsDto;
import com.sun.management.OperatingSystemMXBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.time.Instant;

@Service
public class MonitorService {

    private static final long NETWORK_CACHE_MS = 5_000;

    @Value("${monitoring.internet-check-url:https://www.gstatic.com/generate_204}")
    private String internetCheckUrl;

    @Value("${monitoring.internet-check-timeout-ms:2000}")
    private int internetCheckTimeoutMs;

    private volatile ServerStatsDto.NetworkInfo lastNetwork;
    private volatile long lastNetworkCheckMs;

    public ServerStatsDto snapshot() {
        String host = resolveHostname();
        Instant ts = Instant.now();
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;

        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        double loadSystem = safe(osBean.getSystemCpuLoad());
        double loadProcess = safe(osBean.getProcessCpuLoad());
        double loadAverage = safe(osBean.getSystemLoadAverage());

        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memBean.getNonHeapMemoryUsage();

        long jvmUsed = safeAdd(heap.getUsed(), nonHeap.getUsed());
        long jvmTotal = safeAdd(heap.getCommitted(), nonHeap.getCommitted());

        long jvmHeapTotal = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();
        long jvmNonHeapTotal = nonHeap.getCommitted();

        long sysTotal = osBean.getTotalPhysicalMemorySize();
        long sysFree = osBean.getFreePhysicalMemorySize();
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
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
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

    private long safeAdd(long a, long b) {
        long res = a + b;
        if (res < 0) return Long.MAX_VALUE;
        return res;
    }

    private long safeDiff(long total, long free) {
        long used = total - free;
        return Math.max(0L, used);
    }
}
