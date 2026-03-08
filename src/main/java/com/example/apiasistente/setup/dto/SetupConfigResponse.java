package com.example.apiasistente.setup.dto;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot de configuracion mostrado por el wizard inicial.
 *
 * @param configured si el setup ya fue completado
 * @param ollamaBaseUrl endpoint base de Ollama
 * @param chatModel modelo principal de chat
 * @param fastChatModel modelo de respuesta rapida
 * @param visualModel modelo multimodal
 * @param imageModel modelo de generacion de imagen
 * @param embedModel modelo de embeddings
 * @param responseGuardModel modelo auxiliar de depuracion/guard
 * @param scraperEnabled bandera de scraper habilitado
 * @param scraperUrls urls de scraping configuradas
 * @param scraperOwner propietario/namespace de documentos ingestados
 * @param scraperSource etiqueta de fuente para documentos del scraper
 * @param scraperTags etiquetas aplicadas al conocimiento ingestado
 * @param scraperTickMs intervalo de ejecucion del scraper en ms
 * @param updatedAt fecha de ultima actualizacion persistida
 */
public record SetupConfigResponse(
        boolean configured,
        String ollamaBaseUrl,
        String chatModel,
        String fastChatModel,
        String visualModel,
        String imageModel,
        String embedModel,
        String responseGuardModel,
        boolean scraperEnabled,
        List<String> scraperUrls,
        String scraperOwner,
        String scraperSource,
        String scraperTags,
        long scraperTickMs,
        Instant updatedAt
) {
}
