package com.example.apiasistente.shared.dto;

import java.time.Instant;
import java.util.List;

/**
 * DTO para API Error.
 */
public record ApiError(
        String errorId,
        int status,
        String error,
        String message,
        String path,
        Instant timestamp,
        List<String> details
) {
}

