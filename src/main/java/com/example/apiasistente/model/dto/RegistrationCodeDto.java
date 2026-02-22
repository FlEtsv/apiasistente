package com.example.apiasistente.model.dto;

import java.time.Instant;

public record RegistrationCodeDto(
        Long id,
        String label,
        String codePrefix,
        Instant createdAt,
        Instant expiresAt,
        Instant usedAt,
        Instant revokedAt,
        String usedBy
) {}
