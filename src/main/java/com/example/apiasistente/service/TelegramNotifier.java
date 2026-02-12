package com.example.apiasistente.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final RestClient client;
    private final boolean enabled;
    private final String botToken;
    private final String chatId;

    public TelegramNotifier(
            @Value("${monitoring.alerts.telegram.enabled:true}") boolean enabled,
            @Value("${monitoring.alerts.telegram.bot-token:}") String botToken,
            @Value("${monitoring.alerts.telegram.chat-id:}") String chatId
    ) {
        this.enabled = enabled;
        this.botToken = botToken == null ? "" : botToken.trim();
        this.chatId = chatId == null ? "" : chatId.trim();
        this.client = RestClient.builder()
                .baseUrl("https://api.telegram.org")
                .build();
    }

    public void send(String message) {
        if (!enabled) return;
        if (botToken.isBlank() || chatId.isBlank()) {
            log.warn("Telegram no configurado (bot-token/chat-id vacio). Mensaje omitido.");
            return;
        }

        try {
            client.post()
                    .uri("/bot{token}/sendMessage", botToken)
                    .body(Map.of(
                            "chat_id", chatId,
                            "text", message,
                            "disable_web_page_preview", true
                    ))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.warn("No se pudo enviar alerta a Telegram: {}", ex.getMessage());
        }
    }
}
