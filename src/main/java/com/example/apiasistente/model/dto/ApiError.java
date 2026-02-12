package com.example.apiasistente.model.dto;

import java.time.Instant;
import java.util.List;

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
