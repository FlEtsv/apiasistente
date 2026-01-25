package com.example.apiasistente.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient ollamaRestClient(OllamaProperties props) {
        // Ollama API base: http://localhost:11434/api :contentReference[oaicite:3]{index=3}
        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .build();
    }
}
