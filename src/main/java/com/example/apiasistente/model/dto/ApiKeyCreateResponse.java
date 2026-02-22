package com.example.apiasistente.model.dto;

public record ApiKeyCreateResponse(
        Long id,
        String label,
        String keyPrefix,
        boolean specialModeEnabled,
        String apiKey,
        String sessionId
) {}
