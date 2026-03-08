package com.example.apiasistente.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuracion para ajustar el runtime del chat segun telemetria local.
 */
@Component
@ConfigurationProperties(prefix = "chat.runtime-adaptation")
public class ChatRuntimeAdaptationProperties {

    private boolean enabled = true;
    private long profileTtlMs = 4_000;
    private double emaAlpha = 0.25;

    private double cpuThreshold = 0.88;
    private double memoryThreshold = 0.90;
    private double gpuThreshold = 0.90;
    private long networkLatencyThresholdMs = 1_200;
    private long modelLatencyThresholdMs = 9_000;
    private long turnLatencyThresholdMs = 15_000;

    private double constrainedTemperature = 0.15;
    private double degradedTemperature = 0.10;

    private int normalMaxTokens = 0;
    private int constrainedMaxTokens = 1_200;
    private int degradedMaxTokens = 800;

    private boolean forceFastWhenConstrained = false;
    private boolean forceFastWhenDegraded = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getProfileTtlMs() {
        return profileTtlMs;
    }

    public void setProfileTtlMs(long profileTtlMs) {
        this.profileTtlMs = profileTtlMs;
    }

    public double getEmaAlpha() {
        return emaAlpha;
    }

    public void setEmaAlpha(double emaAlpha) {
        this.emaAlpha = emaAlpha;
    }

    public double getCpuThreshold() {
        return cpuThreshold;
    }

    public void setCpuThreshold(double cpuThreshold) {
        this.cpuThreshold = cpuThreshold;
    }

    public double getMemoryThreshold() {
        return memoryThreshold;
    }

    public void setMemoryThreshold(double memoryThreshold) {
        this.memoryThreshold = memoryThreshold;
    }

    public double getGpuThreshold() {
        return gpuThreshold;
    }

    public void setGpuThreshold(double gpuThreshold) {
        this.gpuThreshold = gpuThreshold;
    }

    public long getNetworkLatencyThresholdMs() {
        return networkLatencyThresholdMs;
    }

    public void setNetworkLatencyThresholdMs(long networkLatencyThresholdMs) {
        this.networkLatencyThresholdMs = networkLatencyThresholdMs;
    }

    public long getModelLatencyThresholdMs() {
        return modelLatencyThresholdMs;
    }

    public void setModelLatencyThresholdMs(long modelLatencyThresholdMs) {
        this.modelLatencyThresholdMs = modelLatencyThresholdMs;
    }

    public long getTurnLatencyThresholdMs() {
        return turnLatencyThresholdMs;
    }

    public void setTurnLatencyThresholdMs(long turnLatencyThresholdMs) {
        this.turnLatencyThresholdMs = turnLatencyThresholdMs;
    }

    public double getConstrainedTemperature() {
        return constrainedTemperature;
    }

    public void setConstrainedTemperature(double constrainedTemperature) {
        this.constrainedTemperature = constrainedTemperature;
    }

    public double getDegradedTemperature() {
        return degradedTemperature;
    }

    public void setDegradedTemperature(double degradedTemperature) {
        this.degradedTemperature = degradedTemperature;
    }

    public int getNormalMaxTokens() {
        return normalMaxTokens;
    }

    public void setNormalMaxTokens(int normalMaxTokens) {
        this.normalMaxTokens = normalMaxTokens;
    }

    public int getConstrainedMaxTokens() {
        return constrainedMaxTokens;
    }

    public void setConstrainedMaxTokens(int constrainedMaxTokens) {
        this.constrainedMaxTokens = constrainedMaxTokens;
    }

    public int getDegradedMaxTokens() {
        return degradedMaxTokens;
    }

    public void setDegradedMaxTokens(int degradedMaxTokens) {
        this.degradedMaxTokens = degradedMaxTokens;
    }

    public boolean isForceFastWhenConstrained() {
        return forceFastWhenConstrained;
    }

    public void setForceFastWhenConstrained(boolean forceFastWhenConstrained) {
        this.forceFastWhenConstrained = forceFastWhenConstrained;
    }

    public boolean isForceFastWhenDegraded() {
        return forceFastWhenDegraded;
    }

    public void setForceFastWhenDegraded(boolean forceFastWhenDegraded) {
        this.forceFastWhenDegraded = forceFastWhenDegraded;
    }
}
