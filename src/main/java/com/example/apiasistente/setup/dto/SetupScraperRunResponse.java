package com.example.apiasistente.setup.dto;

/**
 * Resultado de una ejecucion manual del scraper web.
 */
public record SetupScraperRunResponse(
        boolean executed,
        int processed,
        int updated,
        int skipped,
        int failed,
        String message
) {
}
