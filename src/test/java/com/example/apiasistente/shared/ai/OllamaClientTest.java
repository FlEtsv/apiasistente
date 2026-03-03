package com.example.apiasistente.shared.ai;

import com.example.apiasistente.shared.config.OllamaProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void embedOneUsesEmbedEndpointAndReadsFirstEmbedding() throws Exception {
        AtomicReference<String> pathRef = new AtomicReference<>();
        AtomicReference<String> bodyRef = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/embed", exchange -> {
            pathRef.set(exchange.getRequestURI().getPath());
            bodyRef.set(readBody(exchange));
            respondJson(exchange, """
                    {"model":"test-embed","embeddings":[[0.1,0.2,0.3]]}
                    """);
        });
        server.start();

        OllamaProperties props = new OllamaProperties();
        props.setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/api");
        props.setEmbedModel("test-embed");

        OllamaClient client = new OllamaClient(
                RestClient.builder().baseUrl(props.getBaseUrl()).build(),
                props
        );

        double[] embedding = client.embedOne("hola mundo");

        assertEquals("/api/embed", pathRef.get());
        assertTrue(bodyRef.get().contains("\"model\":\"test-embed\""));
        assertTrue(bodyRef.get().contains("\"input\":\"hola mundo\""));
        assertArrayEquals(new double[]{0.1d, 0.2d, 0.3d}, embedding);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respondJson(HttpExchange exchange, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
