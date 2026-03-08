package com.example.apiasistente.rag.service;

import com.example.apiasistente.rag.config.RagWebScraperProperties;
import com.example.apiasistente.setup.service.SetupConfigService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scraper web simple para poblar el RAG desde URLs configuradas en setup.
 */
@Service
public class RagWebScraperService {

    private static final Logger log = LoggerFactory.getLogger(RagWebScraperService.class);

    private final RagWebScraperProperties properties;
    private final SetupConfigService setupConfigService;
    private final RagService ragService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, String> lastHashByUrl = new ConcurrentHashMap<>();
    private volatile long lastRunAtMs = 0L;

    public RagWebScraperService(RagWebScraperProperties properties,
                                SetupConfigService setupConfigService,
                                RagService ragService) {
        this.properties = properties;
        this.setupConfigService = setupConfigService;
        this.ragService = ragService;
    }

    @Scheduled(
            fixedDelayString = "${rag.web-scraper.scheduler-tick-ms:30000}",
            initialDelayString = "${rag.web-scraper.initial-delay-ms:20000}"
    )
    public void scheduledRun() {
        if (!properties.isEnabled()) {
            return;
        }
        SetupConfigService.ResolvedScraperConfig config = setupConfigService.resolvedScraperConfig();
        if (!config.enabled() || config.urls().isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastRunAtMs < config.tickMs()) {
            return;
        }
        runInternal(config, "AUTO");
    }

    public ScrapeRunResult scrapeNow() {
        SetupConfigService.ResolvedScraperConfig config = setupConfigService.resolvedScraperConfig();
        if (!config.enabled()) {
            return new ScrapeRunResult(false, 0, 0, 0, 0, "Scraper desactivado en setup.");
        }
        if (config.urls().isEmpty()) {
            return new ScrapeRunResult(false, 0, 0, 0, 0, "No hay URLs configuradas.");
        }
        return runInternal(config, "MANUAL");
    }

    private ScrapeRunResult runInternal(SetupConfigService.ResolvedScraperConfig config, String trigger) {
        if (!running.compareAndSet(false, true)) {
            return new ScrapeRunResult(false, 0, 0, 0, 0, "Ya hay una ejecucion en curso.");
        }

        int processed = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;

        try {
            List<String> urls = config.urls();
            int limit = Math.min(urls.size(), Math.max(1, properties.getMaxUrlsPerRun()));
            for (int i = 0; i < limit; i++) {
                String url = urls.get(i);
                processed++;
                try {
                    if (scrapeOne(config, url)) {
                        updated++;
                    } else {
                        skipped++;
                    }
                } catch (Exception ex) {
                    failed++;
                    log.warn("Web scraper fallo url='{}' trigger={} cause={}", url, trigger, ex.getMessage());
                }
            }
            lastRunAtMs = System.currentTimeMillis();
            String message = "OK. Procesadas=" + processed + ", actualizadas=" + updated + ", omitidas=" + skipped + ", fallos=" + failed;
            log.info("rag_web_scraper trigger={} {}", trigger, message);
            return new ScrapeRunResult(true, processed, updated, skipped, failed, message);
        } finally {
            running.set(false);
        }
    }

    private boolean scrapeOne(SetupConfigService.ResolvedScraperConfig config, String url) throws Exception {
        Document doc = Jsoup.connect(url)
                .userAgent(safeUserAgent(properties.getUserAgent()))
                .timeout(Math.max(2000, properties.getTimeoutMs()))
                .get();

        String title = trim(doc.title());
        if (title.isBlank()) {
            title = fallbackTitleFromUrl(url);
        }
        String bodyText = doc.body() != null ? trim(doc.body().text()) : trim(doc.text());
        if (bodyText.isBlank()) {
            return false;
        }

        String clippedBody = clip(bodyText, Math.max(1000, properties.getMaxCharsPerPage()));
        String content = """
                Fuente web: %s
                Titulo: %s
                Extraido en: %s

                %s
                """.formatted(url, title, Instant.now(), clippedBody).trim();

        String hash = sha256(content);
        String previous = lastHashByUrl.get(url);
        if (hash.equals(previous)) {
            return false;
        }

        String documentTitle = "Web :: " + title;
        ragService.upsertStructuredDocumentForOwner(
                config.owner(),
                trim(documentTitle, 200),
                content,
                config.source(),
                config.tags(),
                url,
                List.of()
        );
        lastHashByUrl.put(url, hash);
        return true;
    }

    private String safeUserAgent(String value) {
        String clean = trim(value);
        return clean.isBlank() ? "ApiAsistente-Scraper/1.0" : clean;
    }

    private String fallbackTitleFromUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            String host = trim(uri.getHost());
            String path = trim(uri.getPath());
            if (path.isBlank() || "/".equals(path)) {
                return host.isBlank() ? rawUrl : host;
            }
            return (host.isBlank() ? "" : host) + path;
        } catch (Exception e) {
            return rawUrl;
        }
    }

    private String clip(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars).trim();
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String trim(String value, int maxChars) {
        String clean = trim(value);
        if (clean.length() <= maxChars) {
            return clean;
        }
        return clean.substring(0, maxChars).trim();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    public record ScrapeRunResult(boolean executed,
                                  int processed,
                                  int updated,
                                  int skipped,
                                  int failed,
                                  String message) {
    }
}
