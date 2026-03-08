package com.example.apiasistente.chat.service;

import com.example.apiasistente.chat.config.ChatRuntimeAdaptationProperties;
import com.example.apiasistente.monitoring.dto.ServerStatsDto;
import com.example.apiasistente.monitoring.service.MonitorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatRuntimeAdaptationServiceTest {

    @Mock
    private MonitorService monitorService;

    private ChatRuntimeAdaptationProperties properties;
    private ChatRuntimeAdaptationService service;

    @BeforeEach
    void setUp() {
        properties = new ChatRuntimeAdaptationProperties();
        properties.setProfileTtlMs(0);
        service = new ChatRuntimeAdaptationService(monitorService, properties);
    }

    @Test
    void returnsDegradedProfileWhenPressureIsHigh() {
        when(monitorService.snapshot()).thenReturn(snapshot(
                0.93,
                0.72,
                false,
                1_900,
                true,
                0.97,
                0.88
        ));
        service.recordModelLatency(11_000);
        service.recordTurnLatency(18_000);

        ChatRuntimeAdaptationService.RuntimeProfile profile = service.currentProfile();

        assertEquals(ChatRuntimeAdaptationService.RuntimeMode.DEGRADED, profile.mode());
        assertTrue(profile.preferFastModel());
        assertEquals(0.10, profile.temperatureOverride());
        assertEquals(800, profile.maxTokensOverride());
        assertNotNull(profile.snapshot());
        assertTrue(profile.snapshot().pressureScore() >= 3);
    }

    @Test
    void returnsNormalProfileWithNoOverridesWhenSystemIsStable() {
        when(monitorService.snapshot()).thenReturn(snapshot(
                0.21,
                0.33,
                true,
                35,
                true,
                0.22,
                0.28
        ));

        ChatRuntimeAdaptationService.RuntimeProfile profile = service.currentProfile();

        assertEquals(ChatRuntimeAdaptationService.RuntimeMode.NORMAL, profile.mode());
        assertNull(profile.temperatureOverride());
        assertNull(profile.maxTokensOverride());
        assertEquals("steady", profile.reason());
    }

    @Test
    void skipsMonitoringWhenAdaptationIsDisabled() {
        properties.setEnabled(false);

        ChatRuntimeAdaptationService.RuntimeProfile profile = service.currentProfile();

        assertEquals(ChatRuntimeAdaptationService.RuntimeMode.NORMAL, profile.mode());
        assertEquals("runtime-adaptation-disabled", profile.reason());
        verifyNoInteractions(monitorService);
    }

    private ServerStatsDto snapshot(double cpuSystem,
                                    double memoryRatio,
                                    boolean internetUp,
                                    long networkLatencyMs,
                                    boolean gpuAvailable,
                                    double gpuLoad,
                                    double gpuMemoryLoad) {
        long systemTotal = 1_000L;
        long systemUsed = Math.round(systemTotal * memoryRatio);
        long gpuTotalMb = 10_000L;
        long gpuUsedMb = Math.round(gpuTotalMb * gpuMemoryLoad);
        return new ServerStatsDto(
                "srv-local",
                Instant.parse("2026-03-07T12:00:00Z"),
                3200,
                new ServerStatsDto.CpuInfo(cpuSystem, Math.max(0.01, cpuSystem - 0.06), 1.0),
                new ServerStatsDto.MemoryInfo(240, 512),
                new ServerStatsDto.MemoryInfo(120, 256),
                new ServerStatsDto.MemoryInfo(120, 256),
                new ServerStatsDto.MemoryInfo(systemUsed, systemTotal),
                new ServerStatsDto.MemoryInfo(100, 1000),
                new ServerStatsDto.DiskInfo(300, 1000),
                new ServerStatsDto.ThreadInfo(32, 44, 16),
                new ServerStatsDto.GcInfo(18, 120),
                new ServerStatsDto.NetworkInfo(internetUp, networkLatencyMs, "https://example.test"),
                new ServerStatsDto.GpuInfo(gpuAvailable, gpuLoad, gpuMemoryLoad, gpuUsedMb, gpuTotalMb, "RTX"),
                12
        );
    }
}
