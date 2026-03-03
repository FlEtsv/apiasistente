package com.example.apiasistente.chat.controller;

import com.example.apiasistente.apikey.security.ApiKeyAuthFilter;
import com.example.apiasistente.chat.dto.ChatRequest;
import com.example.apiasistente.chat.dto.ChatResponse;
import com.example.apiasistente.chat.service.ChatQueueService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;

/**
 * Expone el flujo de chat para integraciones autenticadas con API key.
 * Esta capa resuelve si el turno va en modo generico o aislado por usuario externo.
 */
@RestController
@RequestMapping("/api/ext")
public class ExternalChatController {

    private final ChatQueueService chatQueueService;

    public ExternalChatController(ChatQueueService chatQueueService) {
        this.chatQueueService = chatQueueService;
    }

    /**
     * Recibe un turno externo y lo envia a la cola con el scope correcto.
     */
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest req,
                             Principal principal,
                             HttpServletRequest request) {
        // Resuelve el aislamiento antes de entrar al pipeline para no mezclar historiales.
        ChatScope scope = resolveChatScope(req, principal, request);
        return chatQueueService.chatAndWait(
                scope.username(),
                req.getSessionId(),
                req.getMessage(),
                req.getModel(),
                scope.externalUserId(),
                req.getMedia()
        );
    }

    /**
     * Extrae el usuario dueño de la API key y falla temprano si no existe contexto autenticado.
     */
    private String resolveUsername(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "API key requerida.");
        }
        return principal.getName();
    }

    /**
     * Decide si el turno usa el scope generico del owner o un scope aislado por usuario externo.
     */
    private ChatScope resolveChatScope(ChatRequest req, Principal principal, HttpServletRequest request) {
        String username = resolveUsername(principal);
        String externalUserId = normalizeExternalUserId(req.getExternalUserId());
        boolean specialModeRequested = req.isSpecialMode() || hasText(externalUserId);
        if (!specialModeRequested) {
            return new ChatScope(username, null);
        }

        // Solo API keys especiales pueden multiplexar historiales por usuario final.
        boolean specialKey = Boolean.TRUE.equals(request.getAttribute(ApiKeyAuthFilter.ATTR_SPECIAL_KEY));
        if (!specialKey) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Esta API key no tiene modo especial habilitado para aislamiento por usuario externo."
            );
        }
        if (!hasText(externalUserId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "externalUserId es obligatorio cuando specialMode=true."
            );
        }

        Long apiKeyId = (Long) request.getAttribute(ApiKeyAuthFilter.ATTR_API_KEY_ID);
        if (apiKeyId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Contexto de API key no disponible.");
        }

        return new ChatScope(username, "key:" + apiKeyId + "|user:" + externalUserId);
    }

    /**
     * Normaliza el identificador externo para limitar cardinalidad y evitar ruido en la clave de aislamiento.
     */
    private String normalizeExternalUserId(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        String clean = raw.trim();
        if (clean.length() > 120) {
            clean = clean.substring(0, 120);
        }
        return clean;
    }

    /**
     * Centraliza la comprobacion de texto util en esta clase.
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ChatScope(String username, String externalUserId) {
    }
}
