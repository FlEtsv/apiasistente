package com.example.apiasistente.setup.service;

import com.example.apiasistente.shared.config.OllamaProperties;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Centraliza la normalizacion/saneamiento de datos del wizard de setup.
 * <p>
 * Esta clase no persiste estado ni accede a repositorios; solo transforma
 * valores de entrada para que la capa de servicio mantenga una responsabilidad unica.
 */
final class SetupConfigNormalizer {

    static final String DEFAULT_SCRAPER_OWNER = "global";
    static final String DEFAULT_SCRAPER_SOURCE = "web-scraper";
    static final String DEFAULT_SCRAPER_TAGS = "scraper,web,knowledge";
    static final long DEFAULT_SCRAPER_TICK_MS = 300_000L;

    private static final long MIN_SCRAPER_TICK_MS = 10_000L;
    private static final long MAX_SCRAPER_TICK_MS = 24L * 60L * 60L * 1000L;

    private final OllamaProperties defaults;

    SetupConfigNormalizer(OllamaProperties defaults) {
        this.defaults = defaults == null ? new OllamaProperties() : defaults;
    }

    String defaultOllamaBaseUrl() {
        return normalizeOllamaBaseUrl(defaults.getBaseUrl(), defaults.getBaseUrl());
    }

    String defaultChatModel() {
        return normalizeModel(defaults.getChatModel(), "");
    }

    String defaultFastChatModel() {
        return normalizeModel(defaults.getFastChatModel(), "");
    }

    String defaultVisualModel() {
        return normalizeModel(defaults.getVisualModel(), "");
    }

    String defaultImageModel() {
        return normalizeModel(defaults.getImageModel(), "");
    }

    String defaultEmbedModel() {
        return normalizeModel(defaults.getEmbedModel(), "");
    }

    String defaultResponseGuardModel() {
        return normalizeModel(defaults.getResponseGuardModel(), "");
    }

    String normalizeModel(String value, String fallback) {
        String clean = hasText(value) ? value.trim() : "";
        if (clean.isBlank()) {
            clean = hasText(fallback) ? fallback.trim() : "";
        }
        if (clean.length() > 180) {
            clean = clean.substring(0, 180);
        }
        return clean;
    }

    String normalizeOllamaBaseUrl(String raw, String fallback) {
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
            // Si URI no es valida, se deja el texto sin alterar para correccion manual.
        }

        if (base.length() > 320) {
            base = base.substring(0, 320);
        }
        return base;
    }

    List<String> normalizeUrlList(String raw) {
        if (!hasText(raw)) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String[] pieces = raw.split("[\\n\\r,;]+");
        for (String piece : pieces) {
            String clean = piece == null ? "" : piece.trim();
            if (clean.isBlank() || !looksHttpUrl(clean)) {
                continue;
            }
            if (clean.length() > 1000) {
                clean = clean.substring(0, 1000);
            }
            out.add(clean);
        }
        return List.copyOf(out);
    }

    String normalizeOwner(String value) {
        String clean = hasText(value) ? value.trim() : DEFAULT_SCRAPER_OWNER;
        if (clean.isBlank()) {
            clean = DEFAULT_SCRAPER_OWNER;
        }
        if (clean.length() > 120) {
            clean = clean.substring(0, 120);
        }
        return clean;
    }

    String normalizeSource(String value) {
        String clean = hasText(value) ? value.trim() : DEFAULT_SCRAPER_SOURCE;
        if (clean.isBlank()) {
            clean = DEFAULT_SCRAPER_SOURCE;
        }
        if (clean.length() > 160) {
            clean = clean.substring(0, 160);
        }
        return clean;
    }

    String normalizeTags(String value) {
        String clean = hasText(value) ? value.trim().replaceAll("\\s+", " ") : DEFAULT_SCRAPER_TAGS;
        if (clean.isBlank()) {
            clean = DEFAULT_SCRAPER_TAGS;
        }
        if (clean.length() > 1000) {
            clean = clean.substring(0, 1000);
        }
        return clean;
    }

    long normalizeTickMs(Long value) {
        long raw = value == null ? DEFAULT_SCRAPER_TICK_MS : value;
        if (raw < MIN_SCRAPER_TICK_MS) {
            return MIN_SCRAPER_TICK_MS;
        }
        if (raw > MAX_SCRAPER_TICK_MS) {
            return MAX_SCRAPER_TICK_MS;
        }
        return raw;
    }

    private boolean looksHttpUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
