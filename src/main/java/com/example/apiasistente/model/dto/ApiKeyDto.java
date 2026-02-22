package com.example.apiasistente.model.dto;

import java.time.Instant;

public record ApiKeyDto(
        Long id,
        String label,
        String keyPrefix,
        boolean specialModeEnabled,
        Instant createdAt,
        Instant lastUsedAt,
        Instant revokedAt
) {}
