package com.example.apiasistente.monitoring.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Parser centralizado de filtros temporales usados por endpoints de monitor.
 */
final class MonitoringSinceParser {

    private MonitoringSinceParser() {
    }

    /**
     * Convierte un valor ISO-8601 a {@link Instant}.
     *
     * @param raw valor recibido por query param (puede ser null/vacio)
     * @return instante parseado o {@code null} si no se envia filtro
     * @throws ResponseStatusException si el formato no es ISO-8601 valido
     */
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
