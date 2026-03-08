package com.example.apiasistente.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuracion para aprender logs de la app dentro del RAG.
 */
@Component
@ConfigurationProperties(prefix = "rag.log-learning")
public class RagLogLearningProperties {

    private boolean enabled = true;
    private String owner = "global";
    private String source = "app-log";
    private String tags = "logs,incident,runtime";
    private int tailBytes = 262144;
    private int maxLines = 800;
    private int maxChars = 32000;
    private int contextLines = 2;
    private boolean includeOnlyProblematic = true;
    private boolean redactSecrets = true;
    private List<String> paths = new ArrayList<>(List.of(
            "data/audit/chat-trace.jsonl",
            "data/logs/apiasistente.log"
    ));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    public int getTailBytes() {
        return tailBytes;
    }

    public void setTailBytes(int tailBytes) {
        this.tailBytes = tailBytes;
    }

    public int getMaxLines() {
        return maxLines;
    }

    public void setMaxLines(int maxLines) {
        this.maxLines = maxLines;
    }

    public int getMaxChars() {
        return maxChars;
    }

    public void setMaxChars(int maxChars) {
        this.maxChars = maxChars;
    }

    public int getContextLines() {
        return contextLines;
    }

    public void setContextLines(int contextLines) {
        this.contextLines = contextLines;
    }

    public boolean isIncludeOnlyProblematic() {
        return includeOnlyProblematic;
    }

    public void setIncludeOnlyProblematic(boolean includeOnlyProblematic) {
        this.includeOnlyProblematic = includeOnlyProblematic;
    }

    public boolean isRedactSecrets() {
        return redactSecrets;
    }

    public void setRedactSecrets(boolean redactSecrets) {
        this.redactSecrets = redactSecrets;
    }

    public List<String> getPaths() {
        return paths;
    }

    public void setPaths(List<String> paths) {
        this.paths = paths == null ? List.of() : List.copyOf(paths);
    }
}
