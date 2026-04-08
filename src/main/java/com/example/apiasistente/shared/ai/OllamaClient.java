package com.example.apiasistente.shared.ai;

import com.example.apiasistente.chat.service.ChatRuntimeAdaptationService;
import com.example.apiasistente.shared.config.OllamaProperties;
import com.example.apiasistente.setup.service.SetupConfigService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente HTTP para Ollama.
 */
@Component
public class OllamaClient {

    private static final double DEFAULT_TEMPERATURE = 0.2d;
    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final RestClient ollama;
    private final OllamaProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private ChatRuntimeAdaptationService runtimeAdaptationService;
    private SetupConfigService setupConfigService;

    public OllamaClient(RestClient ollamaRestClient, OllamaProperties props) {
        this.ollama = ollamaRestClient;
        this.props = props;
    }

    @Autowired(required = false)
    void setRuntimeAdaptationService(ChatRuntimeAdaptationService runtimeAdaptationService) {
        this.runtimeAdaptationService = runtimeAdaptationService;
    }

    @Autowired(required = false)
    void setSetupConfigService(SetupConfigService setupConfigService) {
        this.setupConfigService = setupConfigService;
    }

    public String chat(List<Message> messages) {
        return chat(messages, resolveDefaultChatModel());
    }

    /**
     * Ejecuta el chat con el modelo indicado (si es nulo, usa el configurado).
     */
    public String chat(List<Message> messages, String model) {
        String resolvedModel = (model == null || model.isBlank()) ? resolveDefaultChatModel() : model;
        if (resolvedModel == null || resolvedModel.isBlank()) {
            throw new IllegalStateException("No hay modelo de chat configurado (ollama.chat-model/fast-chat-model) ni se envio uno en la peticion.");
        }
        ChatRuntimeAdaptationService.RuntimeProfile runtimeProfile = currentRuntimeProfile();
        double temperature = resolveTemperature(runtimeProfile);
        Integer numPredict = resolveMaxTokens(runtimeProfile);
        ChatRequest req = new ChatRequest(
                resolvedModel,
                messages,
                props.isStream(),
                buildOptions(temperature, numPredict)
        );

        long startNanos = System.nanoTime();
        try {
            ChatResponse res = resolveClient().post()
                    .uri("/chat")
                    .body(req)
                    .retrieve()
                    .body(ChatResponse.class);

            if (res == null || res.message == null) return "";
            return res.message.content == null ? "" : res.message.content;
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            throw new IllegalStateException(
                    "Ollama chat fallo. Status=" + e.getStatusCode() +
                            " Body=" + e.getResponseBodyAsString(),
                    e
            );
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("Ollama chat no disponible: " + safeMessage(e), e);
        } catch (RestClientException e) {
            throw new IllegalStateException("Ollama chat fallo: " + safeMessage(e), e);
        } finally {
            recordModelLatency(startNanos);
        }
    }

    /**
     * Retrieval usa un solo embedding por consulta; se alinea con el endpoint actual `/embed`.
     */
    public double[] embedOne(String text) {
        try {
            EmbedRequest req = new EmbedRequest(requireEmbedModel(), text);
            EmbedResponse res = resolveClient().post()
                    .uri("/embed")
                    .body(req)
                    .retrieve()
                    .body(EmbedResponse.class);

            if (res == null || res.embeddings == null || res.embeddings.isEmpty()) {
                return new double[0];
            }

            List<Double> firstEmbedding = res.embeddings.get(0);
            if (firstEmbedding == null || firstEmbedding.isEmpty()) {
                return new double[0];
            }
            return toPrimitive(firstEmbedding);
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            throw new IllegalStateException(
                    "Ollama embed fallo. Status=" + e.getStatusCode() +
                            " Body=" + e.getResponseBodyAsString(),
                    e
            );
        }
    }

    public List<double[]> embedMany(List<String> texts) {
        EmbedRequest req = new EmbedRequest(requireEmbedModel(), texts);
        EmbedResponse res = resolveClient().post()
                .uri("/embed")
                .body(req)
                .retrieve()
                .body(EmbedResponse.class);

        if (res == null || res.embeddings == null) return List.of();
        return res.embeddings.stream().map(this::toPrimitive).toList();
    }

    public String toJson(double[] v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo serializar embedding", e);
        }
    }

    public double[] fromJson(String json) {
        try {
            return mapper.readValue(json, double[].class);
        } catch (Exception e) {
            return new double[0];
        }
    }

    private double[] toPrimitive(List<Double> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
        return out;
    }

    public record Message(String role, String content, List<String> images) {
        public Message(String role, String content) {
            this(role, content, List.of());
        }
    }

    private String requireEmbedModel() {
        String model = resolveConfiguredEmbedModel();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("No hay modelo de embeddings configurado (ollama.embed-model).");
        }
        return model.trim();
    }

    private double resolveTemperature(ChatRuntimeAdaptationService.RuntimeProfile runtimeProfile) {
        Double configured = props.getTemperature();
        double base = sanitizeTemperature(configured);
        if (runtimeProfile == null || runtimeProfile.temperatureOverride() == null) {
            return base;
        }
        return sanitizeTemperature(runtimeProfile.temperatureOverride());
    }

    private Integer resolveMaxTokens(ChatRuntimeAdaptationService.RuntimeProfile runtimeProfile) {
        if (runtimeProfile == null) {
            return null;
        }
        Integer candidate = runtimeProfile.maxTokensOverride();
        if (candidate == null || candidate <= 0) {
            return null;
        }
        return candidate;
    }

    private Map<String, Object> buildOptions(double temperature, Integer numPredict) {
        LinkedHashMap<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", temperature);
        if (numPredict != null && numPredict > 0) {
            options.put("num_predict", numPredict);
        }
        // Limitar ventana de contexto a lo configurado para evitar que modelos grandes consuman
        // toda la RAM/VRAM con historiales largos. 0 = usa el default del modelo.
        int numCtx = props.getNumCtx();
        if (numCtx > 0) {
            options.put("num_ctx", numCtx);
        }
        // Mantener siempre el system prompt en contexto aunque se rote la ventana
        int numKeep = props.getNumKeep();
        if (numKeep > 0) {
            options.put("num_keep", numKeep);
        }
        return options;
    }

    private double sanitizeTemperature(Double configured) {
        if (configured == null || !Double.isFinite(configured)) {
            return DEFAULT_TEMPERATURE;
        }
        if (configured < 0.0d) {
            return 0.0d;
        }
        if (configured > 1.0d) {
            return 1.0d;
        }
        return configured;
    }

    private ChatRuntimeAdaptationService.RuntimeProfile currentRuntimeProfile() {
        if (runtimeAdaptationService == null) {
            return null;
        }
        ChatRuntimeAdaptationService.RuntimeProfile profile = runtimeAdaptationService.currentProfile();
        if (profile == null) {
            return null;
        }
        if (log.isDebugEnabled() && (profile.temperatureOverride() != null || profile.maxTokensOverride() != null)) {
            log.debug(
                    "Runtime adaptation mode={} reason={} temperatureOverride={} maxTokensOverride={} preferFast={}",
                    profile.mode(),
                    profile.reason(),
                    profile.temperatureOverride(),
                    profile.maxTokensOverride(),
                    profile.preferFastModel()
            );
        }
        return profile;
    }

    private void recordModelLatency(long startNanos) {
        if (runtimeAdaptationService == null) {
            return;
        }
        long elapsedMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        runtimeAdaptationService.recordModelLatency(elapsedMs);
    }

    private RestClient resolveClient() {
        String baseUrl = resolveConfiguredBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return ollama;
        }

        String defaultBase = props.getBaseUrl();
        if (defaultBase != null && defaultBase.equalsIgnoreCase(baseUrl.trim())) {
            return ollama;
        }
        return RestClient.builder().baseUrl(baseUrl.trim()).build();
    }

    private String resolveConfiguredBaseUrl() {
        if (setupConfigService != null) {
            return setupConfigService.resolvedOllamaConfig().baseUrl();
        }
        return props.getBaseUrl();
    }

    private String resolveConfiguredEmbedModel() {
        if (setupConfigService != null) {
            return setupConfigService.resolvedOllamaConfig().embedModel();
        }
        return props.getEmbedModel();
    }

    private String resolveDefaultChatModel() {
        if (setupConfigService != null) {
            SetupConfigService.ResolvedOllamaConfig config = setupConfigService.resolvedOllamaConfig();
            if (config.chatModel() != null && !config.chatModel().isBlank()) {
                return config.chatModel();
            }
            return config.fastChatModel();
        }
        if (props.getChatModel() != null && !props.getChatModel().isBlank()) {
            return props.getChatModel();
        }
        return props.getFastChatModel();
    }

    private String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "sin detalle";
        }
        return error.getMessage().replaceAll("\\s+", " ").trim();
    }

    public record ChatRequest(String model, List<Message> messages, boolean stream, Map<String, Object> options) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatResponse(String model, Message message, boolean done) {}

    public record EmbedRequest(String model, Object input) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbedResponse(String model, List<List<Double>> embeddings) {}
}
