package com.example.apiasistente.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades de configuracion para Chat Queue.
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

