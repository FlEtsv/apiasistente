package com.example.apiasistente.rag.service;

import com.example.apiasistente.rag.config.RagWebScraperProperties;
import com.example.apiasistente.rag.repository.KnowledgeDocumentRepository;
import com.example.apiasistente.setup.service.SetupConfigService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
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
    private final KnowledgeDocumentRepository docRepo;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long lastRunAtMs = 0L;

    public RagWebScraperService(RagWebScraperProperties properties,
                                SetupConfigService setupConfigService,
                                RagService ragService,
                                KnowledgeDocumentRepository docRepo) {
        this.properties = properties;
        this.setupConfigService = setupConfigService;
        this.ragService = ragService;
        this.docRepo = docRepo;
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

        // Extrae el contenido principal ignorando nav, footer, aside y scripts.
        // Prioriza etiquetas semánticas (article, main, section[role=main]);
        // si no hay ninguna, cae al body completo como fallback.
        String bodyText = extractMainContent(doc);
        if (bodyText.isBlank()) {
            return false;
        }

        // Descartamos páginas con contenido insignificante (error pages, redirects con body mínimo).
        if (bodyText.length() < 120) {
            log.debug("rag_scraper url='{}' skip=contenido-minimo chars={}", url, bodyText.length());
            return false;
        }

        // Clip respetando límite de párrafo/frase para no cortar a mitad.
        String clippedBody = clipAtBoundary(bodyText, Math.max(1000, properties.getMaxCharsPerPage()));

        // No incluimos Instant.now() en el contenido: variaría en cada run aunque la página no haya cambiado,
        // impidiendo la deduplicación por fingerprint de RagService.
        String content = """
                Fuente web: %s
                Titulo: %s

                %s
                """.formatted(url, title, clippedBody).trim();

        // BUG FIX: dos URLs con el mismo <title> colisionarían en el mismo documentTitle
        // → RagService archiva y recrea en cada scrape (loop infinito).
        // Solución: incluimos un tag corto del URL para garantizar unicidad por URL.
        String urlTag = "[" + Integer.toHexString(url.hashCode() & 0x0FFFFFFF) + "]";
        String documentTitle = trim("Web :: " + title, 188) + " " + urlTag;

        // Snapshot del fingerprint antes del upsert para detectar si hubo cambio real.
        String fingerprintBefore = docRepo
                .findFirstByOwnerAndTitleIgnoreCaseAndActiveTrue(config.owner(), documentTitle)
                .map(d -> d.getContentFingerprint() == null ? "" : d.getContentFingerprint())
                .orElse(null); // null = doc no existía → siempre es nuevo

        var after = ragService.upsertStructuredDocumentForOwner(
                config.owner(),
                documentTitle,
                content,
                config.source(),
                config.tags(),
                url,
                List.of()
        );
        // "actualizado" = doc nuevo (fingerprintBefore null) o fingerprint cambió.
        String fingerprintAfter = after.getContentFingerprint() == null ? "" : after.getContentFingerprint();
        return fingerprintBefore == null || !fingerprintBefore.equals(fingerprintAfter);
    }

    /**
     * Extrae el contenido semántico principal de la página.
     * Elimina nav, header, footer, aside, scripts e iframes antes de leer el texto,
     * y prioriza etiquetas de contenido (article, main, [role=main]) si existen.
     */
    private String extractMainContent(Document doc) {
        // Clonar para no mutar el documento original.
        Document clean = doc.clone();
        clean.select("nav, header, footer, aside, script, style, noscript, iframe, [role=navigation], [role=banner], [role=contentinfo], .cookie-banner, .ads, .advertisement").remove();

        // Buscar contenedor principal semántico.
        String mainText = clean.select("article, main, [role=main], .main-content, .post-content, .entry-content, #content, #main").text();
        if (!mainText.isBlank() && mainText.length() >= 100) {
            return mainText.trim();
        }
        // Fallback al body limpio.
        return trim(clean.body() != null ? clean.body().text() : "");
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

    /**
     * Recorta el texto respetando límites de párrafo o frase para no cortar a mitad de contenido.
     * Si no hay ningún límite natural en el último 30% del texto, corta duro como fallback.
     */
    private String clipAtBoundary(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        // Buscar último salto de párrafo o fin de frase antes del límite.
        int lastParagraph = text.lastIndexOf("\n\n", maxChars);
        int lastNewline   = text.lastIndexOf('\n', maxChars);
        int lastSentence  = Math.max(
                text.lastIndexOf(". ", maxChars),
                Math.max(text.lastIndexOf("? ", maxChars), text.lastIndexOf("! ", maxChars))
        );
        // Priorizar párrafo > frase > línea, siempre que estén en el último 30% del rango.
        int floor = (int) (maxChars * 0.70);
        int boundary = -1;
        if (lastParagraph > floor)  boundary = lastParagraph;
        else if (lastSentence > floor) boundary = lastSentence + 1; // incluir el punto
        else if (lastNewline > floor)  boundary = lastNewline;
        if (boundary > 0) {
            return text.substring(0, boundary).trim();
        }
        return text.substring(0, maxChars).trim();
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

    public record ScrapeRunResult(boolean executed,
                                  int processed,
                                  int updated,
                                  int skipped,
                                  int failed,
                                  String message) {
    }
}
