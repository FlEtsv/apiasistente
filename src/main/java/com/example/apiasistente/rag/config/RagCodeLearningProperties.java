package com.example.apiasistente.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Configuracion para aprender el codigo fuente en RAG y habilitar revisiones automaticas.
 */
@Component
@ConfigurationProperties(prefix = "rag.code-learning")
public class RagCodeLearningProperties {

    private boolean enabled = true;
    private long tickMs = 180_000L;
    private long initialDelayMs = 45_000L;

    private String owner = "global";
    private String source = "code-learning";
    private String tags = "code,review,architecture";
    private String rootPath = ".";

    private List<String> scanPaths = List.of(
            "src/main/java",
            "src/main/resources/static",
            "src/main/resources/templates",
            "src/main/resources"
    );

    private List<String> excludeDirectories = List.of(
            ".git",
            ".idea",
            ".gradle",
            "build",
            "data",
            "node_modules",
            "target"
    );

    private List<String> includeExtensions = List.of(
            ".java",
            ".js",
            ".ts",
            ".tsx",
            ".html",
            ".css",
            ".yml",
            ".yaml",
            ".properties",
            ".md"
    );

    private int maxFilesPerRun = 25;
    private int maxFileSizeBytes = 196_608;
    private int maxCharsPerFile = 22_000;
    private int maxMethodLines = 90;
    private int maxLineLength = 180;
    private int maxRecommendations = 6;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTickMs() {
        return tickMs;
    }

    public void setTickMs(long tickMs) {
        this.tickMs = tickMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getRootPath() {
        return rootPath;
    }

    public void setRootPath(String rootPath) {
        this.rootPath = rootPath;
    }

    public List<String> getScanPaths() {
        return scanPaths;
    }

    public void setScanPaths(List<String> scanPaths) {
        this.scanPaths = scanPaths == null ? List.of() : List.copyOf(scanPaths);
    }

    public List<String> getExcludeDirectories() {
        return excludeDirectories;
    }

    public void setExcludeDirectories(List<String> excludeDirectories) {
        this.excludeDirectories = excludeDirectories == null ? List.of() : List.copyOf(excludeDirectories);
    }

    public List<String> getIncludeExtensions() {
        return includeExtensions;
    }

    public void setIncludeExtensions(List<String> includeExtensions) {
        this.includeExtensions = includeExtensions == null ? List.of() : List.copyOf(includeExtensions);
    }

    public int getMaxFilesPerRun() {
        return maxFilesPerRun;
    }

    public void setMaxFilesPerRun(int maxFilesPerRun) {
        this.maxFilesPerRun = maxFilesPerRun;
    }

    public int getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(int maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public int getMaxCharsPerFile() {
        return maxCharsPerFile;
    }

    public void setMaxCharsPerFile(int maxCharsPerFile) {
        this.maxCharsPerFile = maxCharsPerFile;
    }

    public int getMaxMethodLines() {
        return maxMethodLines;
    }

    public void setMaxMethodLines(int maxMethodLines) {
        this.maxMethodLines = maxMethodLines;
    }

    public int getMaxLineLength() {
        return maxLineLength;
    }

    public void setMaxLineLength(int maxLineLength) {
        this.maxLineLength = maxLineLength;
    }

    public int getMaxRecommendations() {
        return maxRecommendations;
    }

    public void setMaxRecommendations(int maxRecommendations) {
        this.maxRecommendations = maxRecommendations;
    }
}
