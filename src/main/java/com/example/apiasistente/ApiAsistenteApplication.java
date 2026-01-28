package com.example.apiasistente;

import com.example.apiasistente.config.ChatQueueProperties;
import com.example.apiasistente.config.OllamaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({OllamaProperties.class, ChatQueueProperties.class})
public class ApiAsistenteApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiAsistenteApplication.class, args);
    }
}
