package com.example.apiasistente.setup.service;

import com.example.apiasistente.shared.config.OllamaProperties;
import com.example.apiasistente.setup.dto.SetupConfigRequest;
import com.example.apiasistente.setup.dto.SetupConfigResponse;
import com.example.apiasistente.setup.entity.AppSetupConfig;
import com.example.apiasistente.setup.repository.AppSetupConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Orquesta lectura y escritura de la configuracion del wizard inicial.
 * <p>
 * La normalizacion de payloads se delega en {@link SetupConfigNormalizer} para
 * mantener responsabilidades separadas entre persistencia y saneamiento.
 */
@Service
public class SetupConfigService {

    private final AppSetupConfigRepository repository;
    private final SetupConfigNormalizer normalizer;

    public SetupConfigService(AppSetupConfigRepository repository, OllamaProperties defaults) {
        this.repository = repository;
        this.normalizer = new SetupConfigNormalizer(defaults);
    }

    /**
     * Indica si la instalacion ya tiene configuracion persistida y marcada como valida.
     *
     * @return {@code true} cuando el setup ya fue completado
     */
    public boolean isConfigured() {
        return repository.findTopByOrderByIdAsc()
                .map(AppSetupConfig::isConfigured)
                .orElse(false);
    }

    /**
     * Obtiene el estado actual del wizard. Si no existe registro, devuelve defaults.
     *
     * @return snapshot actual para pintar la UI
     */
    public SetupConfigResponse current() {
        return toResponse(repository.findTopByOrderByIdAsc().orElse(null));
    }

    /**
     * Devuelve el preset base (configuracion recomendada del despliegue actual) sin leer valores guardados.
     */
    public SetupConfigResponse defaults() {
        return toResponse(null);
    }

    /**
     * Persiste configuracion del wizard aplicando normalizacion y limites de seguridad.
     *
     * @param request payload recibido desde UI
     * @return configuracion guardada y normalizada
     */
    @Transactional
    public SetupConfigResponse save(SetupConfigRequest request) {
        AppSetupConfig config = repository.findTopByOrderByIdAsc().orElseGet(AppSetupConfig::new);

        config.setConfigured(true);
        config.setOllamaBaseUrl(normalizer.normalizeOllamaBaseUrl(request.getOllamaBaseUrl(), normalizer.defaultOllamaBaseUrl()));
        config.setChatModel(normalizer.normalizeModel(request.getChatModel(), normalizer.defaultChatModel()));
        config.setFastChatModel(normalizer.normalizeModel(request.getFastChatModel(), normalizer.defaultFastChatModel()));
        config.setVisualModel(normalizer.normalizeModel(request.getVisualModel(), normalizer.defaultVisualModel()));
        config.setImageModel(normalizer.normalizeModel(request.getImageModel(), normalizer.defaultImageModel()));
        config.setEmbedModel(normalizer.normalizeModel(request.getEmbedModel(), normalizer.defaultEmbedModel()));
        config.setResponseGuardModel(normalizer.normalizeModel(request.getResponseGuardModel(), normalizer.defaultResponseGuardModel()));

        config.setScraperEnabled(request.isScraperEnabled());
        List<String> urls = normalizer.normalizeUrlList(request.getScraperUrls());
        config.setScraperUrls(String.join("\n", urls));
        config.setScraperOwner(normalizer.normalizeOwner(request.getScraperOwner()));
        config.setScraperSource(normalizer.normalizeSource(request.getScraperSource()));
        config.setScraperTags(normalizer.normalizeTags(request.getScraperTags()));
        config.setScraperTickMs(normalizer.normalizeTickMs(request.getScraperTickMs()));

        AppSetupConfig saved = repository.save(config);
        return toResponse(saved);
    }

    /**
     * Devuelve configuracion efectiva de Ollama para los flujos de chat/RAG.
     *
     * @return configuracion resuelta con fallback a defaults del despliegue
     */
    public ResolvedOllamaConfig resolvedOllamaConfig() {
        Optional<AppSetupConfig> maybe = repository.findTopByOrderByIdAsc();
        if (maybe.isEmpty() || !maybe.get().isConfigured()) {
            return new ResolvedOllamaConfig(
                    normalizer.defaultOllamaBaseUrl(),
                    normalizer.defaultChatModel(),
                    normalizer.defaultFastChatModel(),
                    normalizer.defaultVisualModel(),
                    normalizer.defaultImageModel(),
                    normalizer.defaultEmbedModel(),
                    normalizer.defaultResponseGuardModel()
            );
        }
        AppSetupConfig c = maybe.get();
        return new ResolvedOllamaConfig(
                normalizer.normalizeOllamaBaseUrl(c.getOllamaBaseUrl(), normalizer.defaultOllamaBaseUrl()),
                normalizer.normalizeModel(c.getChatModel(), normalizer.defaultChatModel()),
                normalizer.normalizeModel(c.getFastChatModel(), normalizer.defaultFastChatModel()),
                normalizer.normalizeModel(c.getVisualModel(), normalizer.defaultVisualModel()),
                normalizer.normalizeModel(c.getImageModel(), normalizer.defaultImageModel()),
                normalizer.normalizeModel(c.getEmbedModel(), normalizer.defaultEmbedModel()),
                normalizer.normalizeModel(c.getResponseGuardModel(), normalizer.defaultResponseGuardModel())
        );
    }

    /**
     * Devuelve configuracion efectiva del scraper RAG, independientemente de si existe registro.
     *
     * @return configuracion resuelta para jobs de scraper
     */
    public ResolvedScraperConfig resolvedScraperConfig() {
        Optional<AppSetupConfig> maybe = repository.findTopByOrderByIdAsc();
        if (maybe.isEmpty() || !maybe.get().isConfigured()) {
            return new ResolvedScraperConfig(
                    false,
                    List.of(),
                    SetupConfigNormalizer.DEFAULT_SCRAPER_OWNER,
                    SetupConfigNormalizer.DEFAULT_SCRAPER_SOURCE,
                    SetupConfigNormalizer.DEFAULT_SCRAPER_TAGS,
                    SetupConfigNormalizer.DEFAULT_SCRAPER_TICK_MS
            );
        }

        AppSetupConfig c = maybe.get();
        return new ResolvedScraperConfig(
                c.isScraperEnabled(),
                normalizer.normalizeUrlList(c.getScraperUrls()),
                normalizer.normalizeOwner(c.getScraperOwner()),
                normalizer.normalizeSource(c.getScraperSource()),
                normalizer.normalizeTags(c.getScraperTags()),
                normalizer.normalizeTickMs(c.getScraperTickMs())
        );
    }

    private SetupConfigResponse toResponse(AppSetupConfig c) {
        if (c == null) {
            return new SetupConfigResponse(
                    false,
                    normalizer.defaultOllamaBaseUrl(),
                    normalizer.defaultChatModel(),
                    normalizer.defaultFastChatModel(),
                    normalizer.defaultVisualModel(),
                    normalizer.defaultImageModel(),
                    normalizer.defaultEmbedModel(),
                    normalizer.defaultResponseGuardModel(),
                    false,
                    List.of(),
                    SetupConfigNormalizer.DEFAULT_SCRAPER_OWNER,
                    SetupConfigNormalizer.DEFAULT_SCRAPER_SOURCE,
                    SetupConfigNormalizer.DEFAULT_SCRAPER_TAGS,
                    SetupConfigNormalizer.DEFAULT_SCRAPER_TICK_MS,
                    null
            );
        }

        return new SetupConfigResponse(
                c.isConfigured(),
                normalizer.normalizeOllamaBaseUrl(c.getOllamaBaseUrl(), normalizer.defaultOllamaBaseUrl()),
                normalizer.normalizeModel(c.getChatModel(), normalizer.defaultChatModel()),
                normalizer.normalizeModel(c.getFastChatModel(), normalizer.defaultFastChatModel()),
                normalizer.normalizeModel(c.getVisualModel(), normalizer.defaultVisualModel()),
                normalizer.normalizeModel(c.getImageModel(), normalizer.defaultImageModel()),
                normalizer.normalizeModel(c.getEmbedModel(), normalizer.defaultEmbedModel()),
                normalizer.normalizeModel(c.getResponseGuardModel(), normalizer.defaultResponseGuardModel()),
                c.isScraperEnabled(),
                normalizer.normalizeUrlList(c.getScraperUrls()),
                normalizer.normalizeOwner(c.getScraperOwner()),
                normalizer.normalizeSource(c.getScraperSource()),
                normalizer.normalizeTags(c.getScraperTags()),
                normalizer.normalizeTickMs(c.getScraperTickMs()),
                c.getUpdatedAt()
        );
    }

    /**
     * Configuracion final de Ollama consumida por chat, embeddings e imagen.
     */
    public record ResolvedOllamaConfig(String baseUrl,
                                       String chatModel,
                                       String fastChatModel,
                                       String visualModel,
                                       String imageModel,
                                       String embedModel,
                                       String responseGuardModel) {
    }

    /**
     * Configuracion final del scraper RAG consumida por ejecucion programada/manual.
     */
    public record ResolvedScraperConfig(boolean enabled,
                                        List<String> urls,
                                        String owner,
                                        String source,
                                        String tags,
                                        long tickMs) {
        public ResolvedScraperConfig {
            urls = urls == null ? List.of() : List.copyOf(urls);
            owner = owner == null ? SetupConfigNormalizer.DEFAULT_SCRAPER_OWNER : owner;
            source = source == null ? SetupConfigNormalizer.DEFAULT_SCRAPER_SOURCE : source;
            tags = tags == null ? SetupConfigNormalizer.DEFAULT_SCRAPER_TAGS : tags;
            tickMs = tickMs <= 0 ? SetupConfigNormalizer.DEFAULT_SCRAPER_TICK_MS : tickMs;
        }
    }
}
