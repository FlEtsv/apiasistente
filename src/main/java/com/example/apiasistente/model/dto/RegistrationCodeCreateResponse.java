package com.example.apiasistente.model.dto;

import java.time.Instant;

public record RegistrationCodeCreateResponse(
        Long id,
        String label,
        String codePrefix,
        String code,
        Instant createdAt,
        Instant expiresAt,
        java.util.List<String> permissions
) {}
