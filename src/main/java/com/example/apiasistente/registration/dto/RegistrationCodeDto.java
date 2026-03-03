package com.example.apiasistente.registration.dto;

import java.time.Instant;

/**
 * DTO para Registration Code.
 */
public record RegistrationCodeDto(
        Long id,
        String label,
        String codePrefix,
        Instant createdAt,
        Instant expiresAt,
        Instant usedAt,
        Instant revokedAt,
        String usedBy,
        java.util.List<String> permissions
) {}

