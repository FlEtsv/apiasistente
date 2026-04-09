package com.example.apiasistente.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configuracion de Rest Client.
 * Se establecen timeouts para que las llamadas a Ollama fallen rapidamente en caso de cuelgue,
 * en lugar de bloquear el hilo indefinidamente.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient ollamaRestClient(OllamaProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int connectMs = props.getConnectTimeoutMs() > 0 ? props.getConnectTimeoutMs() : 5_000;
        int readMs = props.getReadTimeoutMs() > 0 ? props.getReadTimeoutMs() : 120_000;
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}

