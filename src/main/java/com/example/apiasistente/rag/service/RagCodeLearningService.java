package com.example.apiasistente.rag.service;

import com.example.apiasistente.rag.config.RagCodeLearningProperties;
import com.example.apiasistente.setup.service.SetupConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Ingesta de codigo fuente en RAG para habilitar ayuda tecnica basada en el proyecto real.
 */
@Service
public class RagCodeLearningService {

    private static final Logger log = LoggerFactory.getLogger(RagCodeLearningService.class);

    private static final Pattern TODO_PATTERN = Pattern.compile("(?i)\\b(TODO|FIXME|HACK|XXX)\\b");
    private static final Pattern BROAD_CATCH_PATTERN = Pattern.compile("(?i)catch\\s*\\(\\s*(Exception|Throwable)\\b");
    private static final Pattern METHOD_START_PATTERN = Pattern.compile(
            "^\\s*(public|protected|private|static|final|synchronized|abstract|default|function|const|let|var).*(\\{|=>\\s*\\{)\\s*$"
    );

    private final RagCodeLearningProperties properties;
    private final RagService ragService;
    private SetupConfigService setupConfigService;

    private final ConcurrentHashMap<Path, String> lastHashesByPath = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger scanCursor = new AtomicInteger(0);

    public RagCodeLearningService(RagCodeLearningProperties properties, RagService ragService) {
        this.properties = properties;
        this.ragService = ragService;
    }

    @Autowired(required = false)
    void setSetupConfigService(SetupConfigService setupConfigService) {
        this.setupConfigService = setupConfigService;
    }

    @Scheduled(
            fixedDelayString = "${rag.code-learning.tick-ms:180000}",
            initialDelayString = "${rag.code-learning.initial-delay-ms:45000}"
    )
    public void scheduledIngest() {
        ingestInternal("AUTO");
    }

    /**
     * Entrada manual reutilizable para admin/tests.
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

        try {
            Path root = resolveRootPath(properties.getRootPath());
            List<Path> candidates = scanCandidates(root);
            if (candidates.isEmpty()) {
                return 0;
            }

            int maxFiles = Math.max(1, properties.getMaxFilesPerRun());
            int start = Math.floorMod(scanCursor.get(), candidates.size());
            List<Path> batch = sliceBatch(candidates, start, maxFiles);

            int ingested = 0;
            int processed = 0;
            for (Path file : batch) {
                processed++;
                try {
                    if (ingestSingleFile(root, file, trigger)) {
                        ingested++;
                    }
                } catch (Exception e) {
                    log.warn("No se pudo aprender codigo '{}' en trigger {}: {}", file, trigger, e.getMessage());
                }
            }

            scanCursor.set(Math.floorMod(start + processed, candidates.size()));
            if (ingested > 0) {
                log.info("RAG code-learning trigger={} ingestedFiles={} scanned={}", trigger, ingested, processed);
            }
            return ingested;
        } finally {
            running.set(false);
        }
    }

    private boolean ingestSingleFile(Path root, Path file, String trigger) throws Exception {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }

        long maxFileSize = Math.max(1L, properties.getMaxFileSizeBytes());
        long size = Files.size(file);
        if (size <= 0 || size > maxFileSize) {
            return false;
        }

        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (!hasText(content)) {
            return false;
        }

        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String clipped = clipFileContent(normalized, Math.max(1_000, properties.getMaxCharsPerFile()));
        if (!hasText(clipped)) {
            return false;
        }

        String relativePath = toRelativeUnixPath(root, file);
        Instant lastModifiedAt = Files.getLastModifiedTime(file).toInstant();
        CodeHints hints = analyzeCode(clipped, properties);
        String document = buildDocument(relativePath, clipped, hints, lastModifiedAt);
        String digest = sha256(document);

        String previousDigest = lastHashesByPath.get(file);
        if (digest.equals(previousDigest)) {
            return false;
        }

        String owner = normalizeOwner(properties.getOwner());
        String title = "Codebase :: " + relativePath;
        String source = normalizeSource(properties.getSource());
        String tags = normalizeTags(properties.getTags(), relativePath);
        ragService.upsertDocumentForOwner(owner, title, document, source, tags);

        lastHashesByPath.put(file, digest);
        log.debug("RAG code-learning ingested file='{}' trigger='{}' chars={}", relativePath, trigger, document.length());
        return true;
    }

    private List<Path> scanCandidates(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }

        LinkedHashSet<Path> files = new LinkedHashSet<>();
        Set<String> allowedExtensions = normalizeExtensions(properties.getIncludeExtensions());
        Set<String> excludedDirs = normalizeDirs(properties.getExcludeDirectories());

        for (String rawScanPath : safeList(properties.getScanPaths())) {
            Path scanPath = root.resolve(rawScanPath).normalize();
            if (!scanPath.startsWith(root) || !Files.isDirectory(scanPath)) {
                continue;
            }

            try (Stream<Path> stream = Files.walk(scanPath)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> !isExcluded(root, path, excludedDirs))
                        .filter(path -> hasAllowedExtension(path, allowedExtensions))
                        .forEach(files::add);
            } catch (Exception e) {
                log.debug("No se pudo escanear path de code-learning '{}': {}", scanPath, e.getMessage());
            }
        }

        return files.stream()
                .sorted(Comparator.comparing(path -> toRelativeUnixPath(root, path)))
                .toList();
    }

    private List<Path> sliceBatch(List<Path> candidates, int start, int limit) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        int size = candidates.size();
        int max = Math.min(Math.max(1, limit), size);
        List<Path> batch = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            int index = (start + i) % size;
            batch.add(candidates.get(index));
        }
        return batch;
    }

    private CodeHints analyzeCode(String content, RagCodeLearningProperties cfg) {
        int lines = countLines(content);
        int todoCount = countMatches(TODO_PATTERN, content);
        int broadCatchCount = countMatches(BROAD_CATCH_PATTERN, content);
        int longMethodCount = countLongMethods(content, Math.max(40, cfg.getMaxMethodLines()));
        int longLineCount = countLongLines(content, Math.max(100, cfg.getMaxLineLength()));

        List<String> recommendations = new ArrayList<>();
        if (todoCount > 0) {
            recommendations.add("Resolver TODO/FIXME pendientes (" + todoCount + ").");
        }
        if (broadCatchCount > 0) {
            recommendations.add("Evitar catch generico de Exception/Throwable (" + broadCatchCount + ").");
        }
        if (longMethodCount > 0) {
            recommendations.add("Dividir metodos largos para mejorar mantenibilidad (" + longMethodCount + ").");
        }
        if (longLineCount > 0) {
            recommendations.add("Reducir lineas demasiado largas para facilitar revision (" + longLineCount + ").");
        }
        if (lines > 900) {
            recommendations.add("Archivo extenso: considerar extraer modulos o componentes.");
        }
        if (recommendations.isEmpty()) {
            recommendations.add("Sin hallazgos criticos en este barrido automatico.");
        }

        int maxRec = Math.max(1, cfg.getMaxRecommendations());
        if (recommendations.size() > maxRec) {
            recommendations = recommendations.subList(0, maxRec);
        }

        return new CodeHints(lines, todoCount, broadCatchCount, longMethodCount, longLineCount, recommendations);
    }

    private int countLongMethods(String content, int maxMethodLines) {
        String[] lines = content.split("\n");
        boolean inMethod = false;
        int depth = 0;
        int startLine = -1;
        int longMethods = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!inMethod) {
                if (METHOD_START_PATTERN.matcher(line).matches() && !line.trim().endsWith(";")) {
                    inMethod = true;
                    startLine = i;
                    depth = braceDelta(line);
                    if (depth <= 0) {
                        inMethod = false;
                    }
                }
                continue;
            }

            depth += braceDelta(line);
            if (depth <= 0) {
                int length = i - startLine + 1;
                if (length > maxMethodLines) {
                    longMethods++;
                }
                inMethod = false;
                startLine = -1;
                depth = 0;
            }
        }

        return longMethods;
    }

    private int braceDelta(String line) {
        if (line == null || line.isEmpty()) {
            return 0;
        }
        int delta = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '{') {
                delta++;
            } else if (ch == '}') {
                delta--;
            }
        }
        return delta;
    }

    private int countLongLines(String content, int maxLineLength) {
        String[] lines = content.split("\n");
        int count = 0;
        for (String line : lines) {
            if (line != null && line.length() > maxLineLength) {
                count++;
            }
        }
        return count;
    }

    private int countLines(String content) {
        if (!hasText(content)) {
            return 0;
        }
        return content.split("\n", -1).length;
    }

    private int countMatches(Pattern pattern, String text) {
        if (pattern == null || !hasText(text)) {
            return 0;
        }
        int count = 0;
        var matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private String buildDocument(String relativePath,
                                 String content,
                                 CodeHints hints,
                                 Instant lastModifiedAt) {
        StringBuilder sb = new StringBuilder(Math.max(1024, content.length() + 640));
        sb.append("Archivo: ").append(relativePath).append('\n');
        sb.append("Ultima modificacion: ").append(lastModifiedAt).append('\n');
        sb.append("Objetivo: permitir respuestas sobre implementacion real y mejoras concretas.\n\n");
        sb.append("Resumen automatico:\n");
        sb.append("- Lineas: ").append(hints.totalLines()).append('\n');
        sb.append("- TODO/FIXME: ").append(hints.todoCount()).append('\n');
        sb.append("- Catch generico (Exception/Throwable): ").append(hints.broadCatchCount()).append('\n');
        sb.append("- Metodos largos detectados: ").append(hints.longMethodCount()).append('\n');
        sb.append("- Lineas largas detectadas: ").append(hints.longLineCount()).append('\n');
        sb.append("- Recomendaciones:\n");
        for (String recommendation : hints.recommendations()) {
            sb.append("  - ").append(recommendation).append('\n');
        }

        sb.append("\nContenido del archivo:\n");
        String language = languageHint(relativePath);
        sb.append("```").append(language).append('\n');
        sb.append(content.strip()).append('\n');
        sb.append("```\n");
        return sb.toString().trim();
    }

    private String languageHint(String relativePath) {
        String ext = extensionOf(relativePath);
        return switch (ext) {
            case ".java" -> "java";
            case ".js" -> "javascript";
            case ".ts" -> "typescript";
            case ".tsx" -> "tsx";
            case ".html" -> "html";
            case ".css" -> "css";
            case ".yml", ".yaml" -> "yaml";
            case ".properties" -> "properties";
            case ".md" -> "markdown";
            default -> "";
        };
    }

    private String clipFileContent(String text, int maxChars) {
        if (!hasText(text)) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }

        int headSize = (int) Math.round(maxChars * 0.70);
        int tailSize = Math.max(0, maxChars - headSize);
        String head = text.substring(0, Math.max(1, headSize)).trim();
        String tail = text.substring(Math.max(0, text.length() - tailSize)).trim();
        return (head + "\n\n// [...contenido recortado...]\n\n" + tail).trim();
    }

    private boolean isExcluded(Path root, Path file, Set<String> excludedDirs) {
        if (root == null || file == null) {
            return true;
        }
        Path relative = root.relativize(file);
        for (Path segment : relative) {
            String clean = segment.toString().trim().toLowerCase(Locale.ROOT);
            if (excludedDirs.contains(clean)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAllowedExtension(Path file, Set<String> allowedExtensions) {
        String ext = extensionOf(file == null ? "" : file.getFileName().toString());
        return !ext.isBlank() && allowedExtensions.contains(ext);
    }

    private String extensionOf(String filename) {
        if (!hasText(filename)) {
            return "";
        }
        int idx = filename.lastIndexOf('.');
        if (idx < 0 || idx == filename.length() - 1) {
            return "";
        }
        return filename.substring(idx).toLowerCase(Locale.ROOT);
    }

    private Set<String> normalizeExtensions(List<String> values) {
        Set<String> out = new HashSet<>();
        for (String value : safeList(values)) {
            String clean = value.trim().toLowerCase(Locale.ROOT);
            if (clean.isBlank()) {
                continue;
            }
            if (!clean.startsWith(".")) {
                clean = "." + clean;
            }
            out.add(clean);
        }
        return out;
    }

    private Set<String> normalizeDirs(List<String> values) {
        Set<String> out = new HashSet<>();
        for (String value : safeList(values)) {
            String clean = value.trim().toLowerCase(Locale.ROOT);
            if (!clean.isBlank()) {
                out.add(clean);
            }
        }
        return out;
    }

    private String toRelativeUnixPath(Path root, Path file) {
        if (root == null || file == null) {
            return "";
        }
        return root.relativize(file).toString().replace('\\', '/');
    }

    private Path resolveRootPath(String rawRoot) {
        try {
            if (!hasText(rawRoot)) {
                return Path.of(".").toAbsolutePath().normalize();
            }
            return Path.of(rawRoot.trim()).toAbsolutePath().normalize();
        } catch (Exception e) {
            return Path.of(".").toAbsolutePath().normalize();
        }
    }

    private List<String> safeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(RagCodeLearningService::hasText)
                .map(String::trim)
                .toList();
    }

    private String normalizeOwner(String owner) {
        String clean = hasText(owner) ? owner.trim() : RagService.GLOBAL_OWNER;
        return clean.isBlank() ? RagService.GLOBAL_OWNER : clean;
    }

    private String normalizeSource(String source) {
        String clean = hasText(source) ? source.trim() : "code-learning";
        if (clean.length() > 160) {
            return clean.substring(0, 160);
        }
        return clean;
    }

    private String normalizeTags(String tags, String relativePath) {
        String base = hasText(tags) ? tags.trim() : "code,review,architecture";
        String ext = extensionOf(relativePath);
        if (hasText(ext)) {
            base = base + ",ext:" + ext.substring(1);
        }
        base = base.replaceAll("\\s+", " ");
        if (base.length() > 1000) {
            return base.substring(0, 1000);
        }
        return base;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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

    private record CodeHints(int totalLines,
                             int todoCount,
                             int broadCatchCount,
                             int longMethodCount,
                             int longLineCount,
                             List<String> recommendations) {
    }
}
