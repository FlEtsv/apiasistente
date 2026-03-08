package com.example.apiasistente.setup.dto;

import java.time.Instant;
import java.util.List;

/**
 * Estado de configuracion mostrado por el wizard.
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
