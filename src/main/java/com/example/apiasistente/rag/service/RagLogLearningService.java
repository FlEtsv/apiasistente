package com.example.apiasistente.rag.service;

import com.example.apiasistente.rag.config.RagLogLearningProperties;
import com.example.apiasistente.setup.service.SetupConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Ingesta automatica de logs de la aplicacion hacia RAG para soporte operativo.
 *
 * Objetivo:
 * - Permitir que el chat responda sobre incidentes reales del sistema.
 * - Mantener foco en errores/anomalias y evitar exponer secretos en bruto.
 */
@Service
public class RagLogLearningService {

    private static final Logger log = LoggerFactory.getLogger(RagLogLearningService.class);

    private static final Pattern ISSUE_PATTERN = Pattern.compile(
            "(?i)\\b(error|exception|fail(?:ed|ure)?|fatal|warn(?:ing)?|timeout|refused|unreachable|out\\s*of\\s*memory|stacktrace|traceback)\\b"
    );
    private static final Pattern AUTH_BEARER_PATTERN = Pattern.compile("(?i)(authorization\\s*[:=]\\s*bearer\\s+)([A-Za-z0-9._~+/=-]{8,})");
    private static final Pattern SECRET_VALUE_PATTERN = Pattern.compile(
            "(?i)((?:api[-_ ]?key|token|secret|password|passwd|pwd)\\s*[:=]\\s*)([^\\s,;]+)"
    );
    private static final Pattern JWT_PATTERN = Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b");
    private static final int UPSERT_MAX_ATTEMPTS = 3;

    private final RagLogLearningProperties properties;
    private final RagService ragService;
    private SetupConfigService setupConfigService;
    private final ConcurrentHashMap<Path, String> lastHashesByPath = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public RagLogLearningService(RagLogLearningProperties properties, RagService ragService) {
        this.properties = properties;
        this.ragService = ragService;
    }

    @Autowired(required = false)
    void setSetupConfigService(SetupConfigService setupConfigService) {
        this.setupConfigService = setupConfigService;
    }

    @Scheduled(
            fixedDelayString = "${rag.log-learning.tick-ms:60000}",
            initialDelayString = "${rag.log-learning.initial-delay-ms:20000}"
    )
    public void scheduledIngest() {
        ingestInternal("AUTO");
    }

    /**
     * Entrada manual reutilizable desde tests o futuras acciones de admin.
     */
    public int ingestNow() {
        return ingestInternal("MANUAL");
    }

    private int ingestInternal(String trigger) {
        if (!properties.isEnabled()) {
            return 0;
        }
        if (!isSetupReady()) {
            return 0;
        }
        if (!running.compareAndSet(false, true)) {
            return 0;
        }

        int ingested = 0;
        try {
            for (String rawPath : safePaths(properties.getPaths())) {
                Path path = normalizePath(rawPath);
                if (path == null || !Files.isRegularFile(path)) {
                    continue;
                }
                try {
                    if (ingestSinglePath(path, trigger)) {
                        ingested++;
                    }
                } catch (Exception e) {
                    log.warn("No se pudo aprender log '{}' en trigger {}: {}", path, trigger, e.getMessage());
                }
            }
            if (ingested > 0) {
                log.info("RAG log-learning trigger={} ingestedFiles={}", trigger, ingested);
            }
            return ingested;
        } finally {
            running.set(false);
        }
    }

    private boolean ingestSinglePath(Path path, String trigger) throws Exception {
        String tail = readTailUtf8(path, Math.max(8_192, properties.getTailBytes()));
        if (!hasText(tail)) {
            return false;
        }

        String relevant = extractRelevantLines(tail);
        if (!hasText(relevant)) {
            return false;
        }

        String sanitized = properties.isRedactSecrets() ? redactSecrets(relevant) : relevant;
        String clipped = clipCharsFromEnd(sanitized, Math.max(2_000, properties.getMaxChars()));
        Instant lastModifiedAt = Files.getLastModifiedTime(path).toInstant();
        String document = buildDocument(path, clipped, lastModifiedAt);
        if (!hasText(document)) {
            return false;
        }

        String digest = sha256(document);
        String previousDigest = lastHashesByPath.get(path);
        if (digest.equals(previousDigest)) {
            return false;
        }

        String owner = normalizeOwner(properties.getOwner());
        String title = "Runtime Logs :: " + path.getFileName();
        String source = normalizeSource(properties.getSource());
        String tags = normalizeTags(properties.getTags());

        upsertWithRetry(owner, title, document, source, tags);
        lastHashesByPath.put(path, digest);
        log.debug("RAG log-learning ingested path='{}' trigger='{}' sizeChars={}", path, trigger, document.length());
        return true;
    }

    private void upsertWithRetry(String owner,
                                 String title,
                                 String document,
                                 String source,
                                 String tags) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= UPSERT_MAX_ATTEMPTS; attempt++) {
            try {
                ragService.upsertDocumentForOwner(owner, title, document, source, tags);
                return;
            } catch (RuntimeException ex) {
                last = ex;
                if (!isTransientSqlContention(ex) || attempt >= UPSERT_MAX_ATTEMPTS) {
                    throw ex;
                }
                long backoffMs = 120L * attempt;
                log.warn(
                        "RAG log-learning contention en upsert intento={} de {} title='{}'. Reintentando en {} ms. cause={}",
                        attempt,
                        UPSERT_MAX_ATTEMPTS,
                        title,
                        backoffMs,
                        compactMessage(ex)
                );
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Ingesta de logs interrumpida durante reintento.", ie);
                }
            }
        }
        if (last != null) {
            throw last;
        }
    }

    private String buildDocument(Path path, String body, Instant lastModifiedAt) {
        if (!hasText(body)) {
            return "";
        }
        return """
                Fuente de log: %s
                Ultima modificacion archivo: %s
                Objetivo: detectar sucesos anormales y apoyar diagnostico/correccion.

                %s
                """.formatted(path.toAbsolutePath(), lastModifiedAt, body.trim()).trim();
    }

    private String extractRelevantLines(String rawText) {
        String normalized = rawText.replace("\r\n", "\n").replace('\r', '\n');
        String[] all = normalized.split("\n");
        if (all.length == 0) {
            return "";
        }

        List<String> lines = new ArrayList<>(all.length);
        for (String line : all) {
            if (line == null) {
                continue;
            }
            String clean = line.stripTrailing();
            if (!clean.isEmpty()) {
                lines.add(clean);
            }
        }
        if (lines.isEmpty()) {
            return "";
        }

        int maxLines = Math.max(40, properties.getMaxLines());
        List<String> selected;
        if (!properties.isIncludeOnlyProblematic()) {
            selected = lines;
        } else {
            selected = selectProblematicWithContext(lines, Math.max(0, properties.getContextLines()));
            if (selected.isEmpty()) {
                selected = lines;
            }
        }

        if (selected.size() > maxLines) {
            selected = selected.subList(selected.size() - maxLines, selected.size());
        }
        return String.join("\n", selected).trim();
    }

    private List<String> selectProblematicWithContext(List<String> lines, int contextLines) {
        Set<Integer> keepIndexes = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!ISSUE_PATTERN.matcher(line).find()) {
                continue;
            }
            int from = Math.max(0, i - contextLines);
            int to = Math.min(lines.size() - 1, i + contextLines);
            for (int j = from; j <= to; j++) {
                keepIndexes.add(j);
            }
        }
        if (keepIndexes.isEmpty()) {
            return List.of();
        }

        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (keepIndexes.contains(i)) {
                out.add(lines.get(i));
            }
        }
        return out;
    }

    private String redactSecrets(String text) {
        if (!hasText(text)) {
            return "";
        }
        String redacted = AUTH_BEARER_PATTERN.matcher(text).replaceAll("$1[REDACTED]");
        redacted = SECRET_VALUE_PATTERN.matcher(redacted).replaceAll("$1[REDACTED]");
        redacted = JWT_PATTERN.matcher(redacted).replaceAll("[JWT_REDACTED]");
        return redacted;
    }

    private String clipCharsFromEnd(String text, int maxChars) {
        if (!hasText(text)) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        String trimmed = text.substring(text.length() - maxChars).trim();
        return "[...recortado...]\n" + trimmed;
    }

    private String readTailUtf8(Path path, int maxBytes) throws Exception {
        long size = Files.size(path);
        if (size <= 0) {
            return "";
        }

        long start = Math.max(0L, size - Math.max(1, maxBytes));
        int length = (int) Math.min(Integer.MAX_VALUE, size - start);
        byte[] bytes = new byte[length];

        try (var channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // loop
            }
        }

        String text = new String(bytes, StandardCharsets.UTF_8);
        if (start > 0) {
            int firstBreak = text.indexOf('\n');
            if (firstBreak >= 0 && firstBreak + 1 < text.length()) {
                text = text.substring(firstBreak + 1);
            }
        }
        return text;
    }

    private List<String> safePaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        return paths.stream()
                .filter(RagLogLearningService::hasText)
                .map(String::trim)
                .toList();
    }

    private Path normalizePath(String rawPath) {
        if (!hasText(rawPath)) {
            return null;
        }
        try {
            return Path.of(rawPath.trim()).normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeOwner(String owner) {
        String clean = hasText(owner) ? owner.trim() : RagService.GLOBAL_OWNER;
        return clean.isBlank() ? RagService.GLOBAL_OWNER : clean;
    }

    private String normalizeSource(String source) {
        String clean = hasText(source) ? source.trim() : "app-log";
        if (clean.length() > 160) {
            return clean.substring(0, 160);
        }
        return clean;
    }

    private String normalizeTags(String tags) {
        if (!hasText(tags)) {
            return "logs,incident,runtime";
        }
        String clean = tags.trim().replaceAll("\\s+", " ");
        if (clean.length() > 1000) {
            return clean.substring(0, 1000);
        }
        return clean;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isTransientSqlContention(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = compactMessage(current);
            if (message.contains("deadlock found when trying to get lock")
                    || message.contains("lock wait timeout exceeded")
                    || message.contains("sqlstate: 40001")
                    || message.contains("could not serialize")
                    || message.contains("cannot acquire lock")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String compactMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "sin detalle";
        }
        return error.getMessage().replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private boolean isSetupReady() {
        return setupConfigService == null || setupConfigService.isConfigured();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible.", e);
        }
    }
}
