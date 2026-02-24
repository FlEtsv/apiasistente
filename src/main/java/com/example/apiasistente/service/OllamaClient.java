package com.example.apiasistente.service;

import com.example.apiasistente.config.OllamaProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class OllamaClient {

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
        ChatRequest req = new ChatRequest(
                resolvedModel,
                messages,
                props.isStream() ? true : false,
                Map.of("temperature", 0.2)
        );

        ChatResponse res = ollama.post()
                .uri("/chat")
                .body(req)
                .retrieve()
                .body(ChatResponse.class);

        if (res == null || res.message == null) return "";
        return res.message.content == null ? "" : res.message.content;
    }

    public double[] embedOne(String text) {
        try {
            String model = props.getEmbedModel().trim();
            EmbeddingsRequest req = new EmbeddingsRequest(model, text);

            EmbeddingsResponse res = ollama.post()
                    .uri("/embeddings")
                    .body(req)
                    .retrieve()
                    .body(EmbeddingsResponse.class);

            if (res == null || res.embedding() == null || res.embedding().isEmpty()) return new double[0];
            return toPrimitive(res.embedding());

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            throw new IllegalStateException(
                    "Ollama embeddings falló. Status=" + e.getStatusCode() +
                            " Body=" + e.getResponseBodyAsString(),
                    e
            );
        }
    }

    // DTOs (ajústalos a tu estilo/paquete)
    public record EmbeddingsRequest(String model, String prompt) {}
    public record EmbeddingsResponse(java.util.List<Double> embedding) {}


    public List<double[]> embedMany(List<String> texts) {
        EmbedRequest req = new EmbedRequest(props.getEmbedModel(), texts);
        EmbedResponse res = ollama.post()
                .uri("/embed")
                .body(req)
                .retrieve()
                .body(EmbedResponse.class);

        if (res == null || res.embeddings == null) return List.of();
        return res.embeddings.stream().map(this::toPrimitive).toList();
    }

    public String toJson(double[] v) {
        try { return mapper.writeValueAsString(v); }
        catch (Exception e) { throw new IllegalStateException("No se pudo serializar embedding", e); }
    }

    public double[] fromJson(String json) {
        try { return mapper.readValue(json, double[].class); }
        catch (Exception e) { return new double[0]; }
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

    public record ChatRequest(String model, List<Message> messages, boolean stream, Map<String, Object> options) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatResponse(String model, Message message, boolean done) {}

    public record EmbedRequest(String model, Object input) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbedResponse(String model, List<List<Double>> embeddings) {}
}
