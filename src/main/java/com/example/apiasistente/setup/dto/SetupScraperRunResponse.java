package com.example.apiasistente.setup.dto;

/**
 * Resultado de una ejecucion manual del scraper web.
 *
 * @param executed si se ejecuto la corrida
 * @param processed urls evaluadas
 * @param updated documentos actualizados
 * @param skipped urls omitidas
 * @param failed urls con error
 * @param message resumen textual de la corrida
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
