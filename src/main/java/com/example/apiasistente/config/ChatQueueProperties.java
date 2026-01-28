package com.example.apiasistente.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de la cola por sesión para suavizar picos de tráfico.
 */
@ConfigurationProperties(prefix = "chat.queue")
public class ChatQueueProperties {

    /**
     * Retardo en milisegundos antes de procesar cada mensaje de la cola.
     */
    private long delayMs = 25;

    public long getDelayMs() {
        return delayMs;
    }

    public void setDelayMs(long delayMs) {
        this.delayMs = delayMs;
    }
}
