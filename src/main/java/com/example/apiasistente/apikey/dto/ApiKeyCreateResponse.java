package com.example.apiasistente.apikey.dto;

/**
 * Respuesta para API Key Create.
 */
public record ApiKeyCreateResponse(
        Long id,
        String label,
        String keyPrefix,
        boolean specialModeEnabled,
        String apiKey,
        String sessionId
) {}

