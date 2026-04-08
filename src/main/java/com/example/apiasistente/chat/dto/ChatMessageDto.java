package com.example.apiasistente.chat.dto;

import java.time.Instant;

/**
 * DTO para Chat Message.
 */
public record ChatMessageDto(
        Long id,
        String role,
        String content,
        Instant createdAt
) {}

