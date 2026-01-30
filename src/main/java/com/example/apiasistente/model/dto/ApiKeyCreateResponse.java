package com.example.apiasistente.model.dto;

public record ApiKeyCreateResponse(
        Long id,
        String label,
        String keyPrefix,
        String apiKey, // ⚠️ solo se devuelve una vez
        String sessionId // sesión aislada para la integración externa
) {}
