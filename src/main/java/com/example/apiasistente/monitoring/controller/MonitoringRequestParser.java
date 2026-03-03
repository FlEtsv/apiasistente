package com.example.apiasistente.monitoring.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;

final class MonitoringRequestParser {

    private MonitoringRequestParser() {
    }

    static Instant parseSince(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parametro 'since' invalido. Usa ISO-8601.");
        }
    }
}
