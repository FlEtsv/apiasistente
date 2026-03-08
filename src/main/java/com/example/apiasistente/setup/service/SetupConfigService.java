package com.example.apiasistente.setup.service;

import com.example.apiasistente.shared.config.OllamaProperties;
import com.example.apiasistente.setup.dto.SetupConfigRequest;
import com.example.apiasistente.setup.dto.SetupConfigResponse;
import com.example.apiasistente.setup.entity.AppSetupConfig;
import com.example.apiasistente.setup.repository.AppSetupConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Gestiona la configuracion de instalacion editable desde pantalla inicial.
 */
@Service
public class SetupConfigService {

    private static final long DEFAULT_SCRAPER_TICK_MS = 300_000L;

    private final AppSetupConfigRepository repository;
    private final OllamaProperties defaults;

    public SetupConfigService(AppSetupConfigRepository repository, OllamaProperties defaults) {
        this.repository = repository;
        this.defaults = defaults;
    }

    public boolean isConfigured() {
        return repository.findTopByOrderByIdAsc()
                .map(AppSetupConfig::isConfigured)
                .orElse(false);
    }

    public SetupConfigResponse current() {
        return toResponse(repository.findTopByOrderByIdAsc().orElse(null));
    }

    /**
     * Devuelve el preset base (configuracion recomendada del despliegue actual) sin leer valores guardados.
     */
    public SetupConfigResponse defaults() {
        return toResponse(null);
    }

    @Transactional
    public SetupConfigResponse save(SetupConfigRequest request) {
        AppSetupConfig config = repository.findTopByOrderByIdAsc().orElseGet(AppSetupConfig::new);

        config.setConfigured(true);
        config.setOllamaBaseUrl(normalizeOllamaBaseUrl(request.getOllamaBaseUrl(), defaults.getBaseUrl()));
        config.setChatModel(normalizeModel(request.getChatModel(), defaults.getChatModel()));
        config.setFastChatModel(normalizeModel(request.getFastChatModel(), defaults.getFastChatModel()));
        config.setVisualModel(normalizeModel(request.getVisualModel(), defaults.getVisualModel()));
        config.setImageModel(normalizeModel(request.getImageModel(), defaults.getImageModel()));
        config.setEmbedModel(normalizeModel(request.getEmbedModel(), defaults.getEmbedModel()));
        config.setResponseGuardModel(normalizeModel(request.getResponseGuardModel(), defaults.getResponseGuardModel()));

        config.setScraperEnabled(request.isScraperEnabled());
        List<String> urls = normalizeUrlList(request.getScraperUrls());
        config.setScraperUrls(String.join("\n", urls));
        config.setScraperOwner(normalizeOwner(request.getScraperOwner()));
        config.setScraperSource(normalizeSource(request.getScraperSource()));
        config.setScraperTags(normalizeTags(request.getScraperTags()));
        config.setScraperTickMs(normalizeTickMs(request.getScraperTickMs()));

        AppSetupConfig saved = repository.save(config);
        return toResponse(saved);
    }

    public ResolvedOllamaConfig resolvedOllamaConfig() {
        Optional<AppSetupConfig> maybe = repository.findTopByOrderByIdAsc();
        if (maybe.isEmpty() || !maybe.get().isConfigured()) {
            return new ResolvedOllamaConfig(
                    normalizeOllamaBaseUrl(defaults.getBaseUrl(), defaults.getBaseUrl()),
                    normalizeModel(defaults.getChatModel(), null),
                    normalizeModel(defaults.getFastChatModel(), null),
                    normalizeModel(defaults.getVisualModel(), null),
                    normalizeModel(defaults.getImageModel(), null),
                    normalizeModel(defaults.getEmbedModel(), null),
                    normalizeModel(defaults.getResponseGuardModel(), null)
            );
        }
        AppSetupConfig c = maybe.get();
        return new ResolvedOllamaConfig(
                normalizeOllamaBaseUrl(c.getOllamaBaseUrl(), defaults.getBaseUrl()),
                normalizeModel(c.getChatModel(), defaults.getChatModel()),
                normalizeModel(c.getFastChatModel(), defaults.getFastChatModel()),
                normalizeModel(c.getVisualModel(), defaults.getVisualModel()),
                normalizeModel(c.getImageModel(), defaults.getImageModel()),
                normalizeModel(c.getEmbedModel(), defaults.getEmbedModel()),
                normalizeModel(c.getResponseGuardModel(), defaults.getResponseGuardModel())
        );
    }

    public ResolvedScraperConfig resolvedScraperConfig() {
        Optional<AppSetupConfig> maybe = repository.findTopByOrderByIdAsc();
        if (maybe.isEmpty() || !maybe.get().isConfigured()) {
            return new ResolvedScraperConfig(
                    false,
                    List.of(),
                    "global",
                    "web-scraper",
                    "scraper,web,knowledge",
                    DEFAULT_SCRAPER_TICK_MS
            );
        }

        AppSetupConfig c = maybe.get();
        return new ResolvedScraperConfig(
                c.isScraperEnabled(),
                normalizeUrlList(c.getScraperUrls()),
                normalizeOwner(c.getScraperOwner()),
                normalizeSource(c.getScraperSource()),
                normalizeTags(c.getScraperTags()),
                normalizeTickMs(c.getScraperTickMs())
        );
    }

    private SetupConfigResponse toResponse(AppSetupConfig c) {
        if (c == null) {
            return new SetupConfigResponse(
                    false,
                    normalizeOllamaBaseUrl(defaults.getBaseUrl(), defaults.getBaseUrl()),
                    normalizeModel(defaults.getChatModel(), ""),
                    normalizeModel(defaults.getFastChatModel(), ""),
                    normalizeModel(defaults.getVisualModel(), ""),
                    normalizeModel(defaults.getImageModel(), ""),
                    normalizeModel(defaults.getEmbedModel(), ""),
                    normalizeModel(defaults.getResponseGuardModel(), ""),
                    false,
                    List.of(),
                    "global",
                    "web-scraper",
                    "scraper,web,knowledge",
                    DEFAULT_SCRAPER_TICK_MS,
                    null
            );
        }

        return new SetupConfigResponse(
                c.isConfigured(),
                normalizeOllamaBaseUrl(c.getOllamaBaseUrl(), defaults.getBaseUrl()),
                normalizeModel(c.getChatModel(), defaults.getChatModel()),
                normalizeModel(c.getFastChatModel(), defaults.getFastChatModel()),
                normalizeModel(c.getVisualModel(), defaults.getVisualModel()),
                normalizeModel(c.getImageModel(), defaults.getImageModel()),
                normalizeModel(c.getEmbedModel(), defaults.getEmbedModel()),
                normalizeModel(c.getResponseGuardModel(), defaults.getResponseGuardModel()),
                c.isScraperEnabled(),
                normalizeUrlList(c.getScraperUrls()),
                normalizeOwner(c.getScraperOwner()),
                normalizeSource(c.getScraperSource()),
                normalizeTags(c.getScraperTags()),
                normalizeTickMs(c.getScraperTickMs()),
                c.getUpdatedAt()
        );
    }

    private String normalizeModel(String value, String fallback) {
        String clean = hasText(value) ? value.trim() : "";
        if (clean.isBlank()) {
            clean = hasText(fallback) ? fallback.trim() : "";
        }
        if (clean.length() > 180) {
            clean = clean.substring(0, 180);
        }
        return clean;
    }

    private String normalizeOllamaBaseUrl(String raw, String fallback) {
        String base = hasText(raw) ? raw.trim() : (hasText(fallback) ? fallback.trim() : "");
        if (base.isBlank()) {
            return "http://localhost:11434/api";
        }

        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        try {
            URI uri = URI.create(base);
            String path = uri.getPath() == null ? "" : uri.getPath().trim();
            if (path.isBlank() || "/".equals(path)) {
                base = base + "/api";
            }
        } catch (Exception ignored) {
            // Si URI no es valida, dejamos el texto tal cual para que el usuario lo corrija en pantalla.
        }

        if (base.length() > 320) {
            base = base.substring(0, 320);
        }
        return base;
    }

    private List<String> normalizeUrlList(String raw) {
        if (!hasText(raw)) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String[] pieces = raw.split("[\\n\\r,;]+");
        for (String piece : pieces) {
            String clean = piece == null ? "" : piece.trim();
            if (clean.isBlank()) {
                continue;
            }
            if (!looksHttpUrl(clean)) {
                continue;
            }
            if (clean.length() > 1000) {
                clean = clean.substring(0, 1000);
            }
            out.add(clean);
        }
        return List.copyOf(out);
    }

    private boolean looksHttpUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private String normalizeOwner(String value) {
        String clean = hasText(value) ? value.trim() : "global";
        if (clean.isBlank()) {
            clean = "global";
        }
        if (clean.length() > 120) {
            clean = clean.substring(0, 120);
        }
        return clean;
    }

    private String normalizeSource(String value) {
        String clean = hasText(value) ? value.trim() : "web-scraper";
        if (clean.isBlank()) {
            clean = "web-scraper";
        }
        if (clean.length() > 160) {
            clean = clean.substring(0, 160);
        }
        return clean;
    }

    private String normalizeTags(String value) {
        String clean = hasText(value) ? value.trim().replaceAll("\\s+", " ") : "scraper,web,knowledge";
        if (clean.isBlank()) {
            clean = "scraper,web,knowledge";
        }
        if (clean.length() > 1000) {
            clean = clean.substring(0, 1000);
        }
        return clean;
    }

    private long normalizeTickMs(Long value) {
        long raw = value == null ? DEFAULT_SCRAPER_TICK_MS : value;
        if (raw < 10_000L) {
            return 10_000L;
        }
        if (raw > 24 * 60 * 60 * 1000L) {
            return 24 * 60 * 60 * 1000L;
        }
        return raw;
    }

    private long normalizeTickMs(long value) {
        return normalizeTickMs(Long.valueOf(value));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ResolvedOllamaConfig(String baseUrl,
                                       String chatModel,
                                       String fastChatModel,
                                       String visualModel,
                                       String imageModel,
                                       String embedModel,
                                       String responseGuardModel) {
    }

    public record ResolvedScraperConfig(boolean enabled,
                                        List<String> urls,
                                        String owner,
                                        String source,
                                        String tags,
                                        long tickMs) {
        public ResolvedScraperConfig {
            urls = urls == null ? List.of() : List.copyOf(urls);
            owner = owner == null ? "global" : owner;
            source = source == null ? "web-scraper" : source;
            tags = tags == null ? "scraper,web,knowledge" : tags;
            tickMs = tickMs <= 0 ? DEFAULT_SCRAPER_TICK_MS : tickMs;
        }
    }
}
