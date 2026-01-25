package com.example.apiasistente.model.dto;

import java.time.Instant;

public record SessionDetailsDto(
        String id,
        String title,
        Instant createdAt,
        Instant lastActivityAt,
        long messageCount,
        Instant lastMessageAt
) {}
