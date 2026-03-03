package com.example.apiasistente.chat.service;

import com.example.apiasistente.chat.dto.ChatMediaInput;
import com.example.apiasistente.chat.dto.ChatMessageDto;
import com.example.apiasistente.chat.dto.ChatResponse;
import com.example.apiasistente.chat.dto.SessionDetailsDto;
import com.example.apiasistente.chat.dto.SessionSummaryDto;
import com.example.apiasistente.chat.entity.ChatMessage;
import com.example.apiasistente.chat.service.flow.ChatHistoryService;
import com.example.apiasistente.chat.service.flow.ChatSessionService;
import com.example.apiasistente.chat.service.flow.ChatTurnService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fachada del dominio de chat.
 * Expone operaciones simples para controllers y cola, y delega la orquestacion real
 * del turno a servicios especializados.
 */
@Service
public class ChatService {

    private final ChatTurnService turnService;
    private final ChatSessionService sessionService;
    private final ChatHistoryService historyService;

    public ChatService(ChatTurnService turnService,
                       ChatSessionService sessionService,
                       ChatHistoryService historyService) {
        this.turnService = turnService;
        this.sessionService = sessionService;
        this.historyService = historyService;
    }

    /**
     * Punto de entrada minimo para un turno sin seleccion explicita de modelo ni adjuntos.
     */
    public ChatResponse chat(String username, String maybeSessionId, String userText) {
        return chat(username, maybeSessionId, userText, null, null, List.of());
    }

    /**
     * Punto de entrada para un turno con modelo solicitado por el cliente.
     */
    public ChatResponse chat(String username, String maybeSessionId, String userText, String requestedModel) {
        return chat(username, maybeSessionId, userText, requestedModel, null, List.of());
    }

    /**
     * Punto de entrada para un turno con adjuntos pero sin aislamiento externo.
     */
    public ChatResponse chat(String username,
                             String maybeSessionId,
                             String userText,
                             String requestedModel,
                             List<ChatMediaInput> media) {
        return chat(username, maybeSessionId, userText, requestedModel, null, media);
    }

    /**
     * Punto de entrada para integraciones externas con aislamiento por usuario final.
     */
    public ChatResponse chat(String username,
                             String maybeSessionId,
                             String userText,
                             String requestedModel,
                             String externalUserId) {
        return chat(username, maybeSessionId, userText, requestedModel, externalUserId, List.of());
    }

    /**
     * Envia el turno completo al orquestador transaccional.
     * Esta es la sobrecarga que concentra todas las variantes anteriores.
     */
    public ChatResponse chat(String username,
                             String maybeSessionId,
                             String userText,
                             String requestedModel,
                             String externalUserId,
                             List<ChatMediaInput> media) {
        return turnService.chat(username, maybeSessionId, userText, requestedModel, externalUserId, media);
    }

    /**
     * Expone el historial serializado para consumo HTTP.
     */
    public List<ChatMessageDto> historyDto(String username, String sessionId) {
        return historyService.historyDto(username, sessionId);
    }

    /**
     * Devuelve entidades del historial para usos internos donde se necesita mas detalle.
     */
    public List<ChatMessage> historyEntitiesForInternalUse(String username, String sessionId) {
        return historyService.historyEntitiesForInternalUse(username, sessionId);
    }

    /**
     * Obtiene o crea la sesion generica activa del usuario.
     */
    public String activeSessionId(String username) {
        return sessionService.activeSessionId(username);
    }

    /**
     * Crea una sesion generica nueva para iniciar una conversacion separada.
     */
    public String newSession(String username) {
        return sessionService.newSession(username);
    }

    /**
     * Lista sesiones resumidas visibles para el usuario.
     */
    public List<SessionSummaryDto> listSessions(String username) {
        return sessionService.listSessions(username);
    }

    /**
     * Reactiva una sesion existente moviendola al tope de actividad.
     */
    public String activateSession(String username, String sessionId) {
        return sessionService.activateSession(username, sessionId);
    }

    /**
     * Actualiza el titulo manual de una sesion.
     */
    public void renameSession(String username, String sessionId, String title) {
        sessionService.renameSession(username, sessionId, title);
    }

    /**
     * Elimina una sesion especifica del usuario.
     */
    public void deleteSession(String username, String sessionId) {
        sessionService.deleteSession(username, sessionId);
    }

    /**
     * Elimina todas las sesiones genericas del usuario y devuelve cuantas borro.
     */
    public int deleteAllSessions(String username) {
        return sessionService.deleteAllSessions(username);
    }

    /**
     * Devuelve metadata detallada de una sesion concreta.
     */
    public SessionDetailsDto sessionDetails(String username, String sessionId) {
        return sessionService.sessionDetails(username, sessionId);
    }
}


