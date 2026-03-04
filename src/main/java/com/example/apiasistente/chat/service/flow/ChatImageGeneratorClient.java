package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.config.ChatImageGenerationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Cliente HTTP simple para generar imágenes desde un modelo remoto.
 */
@Component
public class ChatImageGeneratorClient {

    private final ChatImageGenerationProperties properties;
    private final HttpClient httpClient;

    public ChatImageGeneratorClient(ChatImageGenerationProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, properties.getTimeoutMs())))
                .build();
    }

    public GeneratedImage generate(String prompt, String model) {
        String baseUrl = safeBaseUrl(properties.getBaseUrl());
        if (baseUrl.isBlank()) {
            throw new IllegalStateException("No hay endpoint de generación de imagen configurado.");
        }

        String encodedPrompt = URLEncoder.encode(prompt == null ? "" : prompt, StandardCharsets.UTF_8);
        String encodedModel = URLEncoder.encode(model == null ? "" : model, StandardCharsets.UTF_8);
        String url = "%s/%s?model=%s&width=%d&height=%d&nologo=true&safe=true".formatted(
                baseUrl,
                encodedPrompt,
                encodedModel,
                Math.max(256, properties.getWidth()),
                Math.max(256, properties.getHeight())
        );

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofMillis(Math.max(1, properties.getTimeoutMs())))
                .header("Accept", "image/*")
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Generación de imagen falló. Status=" + status);
            }
            byte[] bytes = response.body() == null ? new byte[0] : response.body();
            if (bytes.length == 0) {
                throw new IllegalStateException("El servicio de imagen devolvió payload vacío.");
            }

            String contentType = response.headers()
                    .firstValue("content-type")
                    .orElse("image/png")
                    .trim();
            return new GeneratedImage(contentType, bytes);
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo generar imagen: " + safeMessage(ex), ex);
        }
    }

    private String safeBaseUrl(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.trim();
        if (clean.endsWith("/")) {
            return clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    private String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "sin detalle";
        }
        return error.getMessage().replaceAll("\\s+", " ").trim();
    }

    public record GeneratedImage(String mimeType, byte[] bytes) {
    }
}
