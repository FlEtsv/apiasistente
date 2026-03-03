package com.example.apiasistente.chat.dto;

import java.time.Instant;

/**
 * DTO para Session Details.
 */
public record SessionDetailsDto(
        String id,
        String title,
        Instant createdAt,
        Instant lastActivityAt,
        long messageCount,
        Instant lastMessageAt
) {}

