package com.example.apiasistente.shared.ai;

import com.example.apiasistente.shared.config.OllamaProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Cliente HTTP para Ollama.
 */
@Component
public class OllamaClient {

    private static final double DEFAULT_TEMPERATURE = 0.2d;

    private final RestClient ollama;
    private final OllamaProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public OllamaClient(RestClient ollamaRestClient, OllamaProperties props) {
        this.ollama = ollamaRestClient;
        this.props = props;
    }

    public String chat(List<Message> messages) {
        return chat(messages, props.getChatModel());
    }

    /**
     * Ejecuta el chat con el modelo indicado (si es nulo, usa el configurado).
     */
    public String chat(List<Message> messages, String model) {
        String resolvedModel = (model == null || model.isBlank()) ? props.getChatModel() : model;
        if (resolvedModel == null || resolvedModel.isBlank()) {
            throw new IllegalStateException("No hay modelo de chat configurado (ollama.chat-model/fast-chat-model) ni se envio uno en la peticion.");
        }
        double temperature = resolveTemperature();
        ChatRequest req = new ChatRequest(
                resolvedModel,
                messages,
                props.isStream(),
                Map.of("temperature", temperature)
        );

        try {
            ChatResponse res = ollama.post()
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
        }
    }

    /**
     * Retrieval usa un solo embedding por consulta; se alinea con el endpoint actual `/embed`.
     */
    public double[] embedOne(String text) {
        try {
            EmbedRequest req = new EmbedRequest(requireEmbedModel(), text);
            EmbedResponse res = ollama.post()
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
        EmbedResponse res = ollama.post()
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
        String model = props.getEmbedModel();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("No hay modelo de embeddings configurado (ollama.embed-model).");
        }
        return model.trim();
    }

    private double resolveTemperature() {
        Double configured = props.getTemperature();
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
