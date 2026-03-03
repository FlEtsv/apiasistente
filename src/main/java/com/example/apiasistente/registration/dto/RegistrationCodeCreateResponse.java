package com.example.apiasistente.registration.dto;

import java.time.Instant;

/**
 * Respuesta para Registration Code Create.
 */
public record RegistrationCodeCreateResponse(
        Long id,
        String label,
        String codePrefix,
        String code,
        Instant createdAt,
        Instant expiresAt,
        java.util.List<String> permissions
) {}

