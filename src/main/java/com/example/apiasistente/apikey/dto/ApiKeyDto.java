package com.example.apiasistente.apikey.dto;

import java.time.Instant;

/**
 * DTO para API Key.
 */
public record ApiKeyDto(
        Long id,
        String label,
        String keyPrefix,
        boolean specialModeEnabled,
        Instant createdAt,
        Instant lastUsedAt,
        Instant revokedAt
) {}

