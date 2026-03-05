package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.config.ChatImageGenerationProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatImageGeneratorClientTest {

    private static final String ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO7sLw8AAAAASUVORK5CYII=";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void generateBuildsComfyPromptAndDecodesBase64Image() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = startServer(exchange -> {
            capturedBody.set(readBody(exchange));
            writeJson(exchange, 200, """
                    {
                      "id": "req-1",
                      "images": ["%s"],
                      "filenames": ["output.png"]
                    }
                    """.formatted(ONE_PIXEL_PNG_BASE64));
        });

        try {
            ChatImageGenerationProperties properties = baseProperties(server);
            properties.setWidth(1152);
            properties.setHeight(896);
            properties.setSteps(8);
            properties.setSeed(12345);
            properties.setCfgScale(1.2);
            properties.setDenoise(0.9);
            properties.setSamplerName("euler");
            properties.setScheduler("simple");

            ChatImageGeneratorClient client = new ChatImageGeneratorClient(properties);
            ChatImageGeneratorClient.GeneratedImage image = client.generate("gato astronauta", "image", null);

            assertEquals("image/png", image.mimeType());
            assertTrue(image.bytes().length > 0);

            JsonNode request = mapper.readTree(capturedBody.get());
            assertNotNull(request.path("id").asText());
            assertFalse(request.path("id").asText().isBlank());
            assertEquals("flux1-schnell-fp8.safetensors",
                    request.path("prompt").path("30").path("inputs").path("ckpt_name").asText());
            assertEquals("gato astronauta",
                    request.path("prompt").path("6").path("inputs").path("text").asText());
            assertEquals(1152, request.path("prompt").path("27").path("inputs").path("width").asInt());
            assertEquals(896, request.path("prompt").path("27").path("inputs").path("height").asInt());
            assertEquals(8, request.path("prompt").path("31").path("inputs").path("steps").asInt());
            assertEquals(12345L, request.path("prompt").path("31").path("inputs").path("seed").asLong());
            assertEquals(1.2d, request.path("prompt").path("31").path("inputs").path("cfg").asDouble());
            assertEquals(0.9d, request.path("prompt").path("31").path("inputs").path("denoise").asDouble());
            assertFalse(request.has("convert_output"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generateIncludesConvertOutputWhenConfigured() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = startServer(exchange -> {
            capturedBody.set(readBody(exchange));
            writeJson(exchange, 200, """
                    {
                      "images": ["%s"],
                      "filenames": ["output.webp"]
                    }
                    """.formatted(ONE_PIXEL_PNG_BASE64));
        });

        try {
            ChatImageGenerationProperties properties = baseProperties(server);
            properties.setConvertFormat("webp");
            properties.setConvertQuality(73);

            ChatImageGeneratorClient client = new ChatImageGeneratorClient(properties);
            ChatImageGeneratorClient.GeneratedImage image = client.generate("montana al amanecer", "image", null);

            assertEquals("image/webp", image.mimeType());
            assertTrue(image.bytes().length > 0);

            JsonNode request = mapper.readTree(capturedBody.get());
            assertEquals("webp", request.path("convert_output").path("format").asText());
            assertEquals(73, request.path("convert_output").path("options").path("quality").asInt());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generateSurfacesStatusAndBodyWhenProviderFails() throws Exception {
        HttpServer server = startServer(exchange -> writeJson(exchange, 500, """
                {"error":"CUDA out of memory"}
                """));

        try {
            ChatImageGeneratorClient client = new ChatImageGeneratorClient(baseProperties(server));

            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> client.generate("ciudad cyberpunk", "image", null)
            );
            assertTrue(error.getMessage().contains("Status=500"));
            assertTrue(error.getMessage().contains("CUDA out of memory"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generateBuildsImg2ImgWorkflowWhenReferenceImageIsProvided() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = startServer(exchange -> {
            capturedBody.set(readBody(exchange));
            writeJson(exchange, 200, """
                    {
                      "images": ["%s"],
                      "filenames": ["output.png"]
                    }
                    """.formatted(ONE_PIXEL_PNG_BASE64));
        });

        try {
            ChatImageGenerationProperties properties = baseProperties(server);
            properties.setImg2imgDenoise(0.8);
            ChatImageGeneratorClient client = new ChatImageGeneratorClient(properties);
            client.generate("hazlo estilo anime", "flux1-dev-fp8.safetensors", "data:image/png;base64," + ONE_PIXEL_PNG_BASE64);

            JsonNode request = mapper.readTree(capturedBody.get());
            JsonNode prompt = request.path("prompt");
            assertEquals("flux1-dev-fp8.safetensors", prompt.path("30").path("inputs").path("ckpt_name").asText());
            assertEquals(ONE_PIXEL_PNG_BASE64, prompt.path("37").path("inputs").path("image").asText());
            assertEquals("image", prompt.path("37").path("inputs").path("upload").asText());
            assertTrue(prompt.path("40").isMissingNode() || prompt.path("40").isNull());
            assertEquals("37", prompt.path("38").path("inputs").path("pixels").get(0).asText());
            assertEquals("38", prompt.path("31").path("inputs").path("latent_image").get(0).asText());
            assertEquals(0.8d, prompt.path("31").path("inputs").path("denoise").asDouble());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generateFallsBackToDefaultCheckpointWhenRequestedCheckpointIsUnavailable() throws Exception {
        AtomicReference<String> firstBody = new AtomicReference<>();
        AtomicReference<String> secondBody = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger(0);
        HttpServer server = startServer(exchange -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstBody.set(readBody(exchange));
                writeJson(exchange, 500, """
                        {
                          "error": {
                            "type": "prompt_outputs_failed_validation",
                            "node_errors": {
                              "30": {
                                "errors": [
                                  { "type": "value_not_in_list", "message": "ckpt not found" }
                                ]
                              }
                            }
                          }
                        }
                        """);
                return;
            }
            secondBody.set(readBody(exchange));
            writeJson(exchange, 200, """
                    {
                      "images": ["%s"],
                      "filenames": ["output.png"]
                    }
                    """.formatted(ONE_PIXEL_PNG_BASE64));
        });

        try {
            ChatImageGenerationProperties properties = baseProperties(server);
            properties.setCheckpoint("flux1-schnell-fp8.safetensors");
            ChatImageGeneratorClient client = new ChatImageGeneratorClient(properties);

            ChatImageGeneratorClient.GeneratedImage image = client.generate(
                    "retrato editorial",
                    "flux1-dev-fp8.safetensors",
                    null
            );

            assertEquals(2, calls.get());
            assertTrue(image.fallbackApplied());
            assertEquals("flux1-dev-fp8.safetensors", image.requestedCheckpoint());
            assertEquals("flux1-schnell-fp8.safetensors", image.checkpoint());

            JsonNode firstReq = mapper.readTree(firstBody.get());
            JsonNode secondReq = mapper.readTree(secondBody.get());
            assertEquals(
                    "flux1-dev-fp8.safetensors",
                    firstReq.path("prompt").path("30").path("inputs").path("ckpt_name").asText()
            );
            assertEquals(
                    "flux1-schnell-fp8.safetensors",
                    secondReq.path("prompt").path("30").path("inputs").path("ckpt_name").asText()
            );
        } finally {
            server.stop(0);
        }
    }

    private ChatImageGenerationProperties baseProperties(HttpServer server) {
        ChatImageGenerationProperties properties = new ChatImageGenerationProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setPromptPath("/prompt");
        properties.setTimeoutMs(10_000);
        properties.setCheckpoint("flux1-schnell-fp8.safetensors");
        return properties;
    }

    private HttpServer startServer(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/prompt", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
