package com.example.apiasistente.model.dto;

import java.time.Instant;

/**
 * DTO para devolver mensajes al frontend sin exponer entidades JPA.
 * Evita LazyInitializationException y evita fugas de datos.
 */
public record ChatMessageDto(
        Long id,
        String role,
        String content,
        Instant createdAt
) {}
