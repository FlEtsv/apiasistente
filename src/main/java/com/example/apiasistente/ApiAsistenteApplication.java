package com.example.apiasistente;

import com.example.apiasistente.chat.config.ChatQueueProperties;
import com.example.apiasistente.shared.config.OllamaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Punto de entrada de la aplicacion ApiAsistente.
 */
@SpringBootApplication
@EnableConfigurationProperties({OllamaProperties.class, ChatQueueProperties.class})
public class ApiAsistenteApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiAsistenteApplication.class, args);
    }
}

