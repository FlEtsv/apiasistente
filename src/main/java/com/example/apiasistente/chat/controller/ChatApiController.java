package com.example.apiasistente.chat.controller;

import com.example.apiasistente.chat.dto.ChatMessageDto;
import com.example.apiasistente.chat.dto.ChatRagTelemetrySnapshotDto;
import com.example.apiasistente.chat.dto.ChatRequest;
import com.example.apiasistente.chat.dto.ChatResponse;
import com.example.apiasistente.chat.dto.RenameSessionRequest;
import com.example.apiasistente.chat.dto.SessionSummaryDto;
import com.example.apiasistente.chat.service.ChatQueueService;
import com.example.apiasistente.chat.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Expone el flujo de chat web y la gestion de sesiones del usuario autenticado.
 * Cada endpoint delega en servicios del dominio para evitar logica de negocio en la capa HTTP.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatApiController {

    private final ChatService chatService;
    private final ChatQueueService chatQueueService;

    public ChatApiController(ChatService chatService, ChatQueueService chatQueueService) {
        this.chatService = chatService;
        this.chatQueueService = chatQueueService;
    }

    /**
     * Devuelve la sesion generica activa del usuario o crea una nueva si no existe.
     */
    @GetMapping("/active")
    public Map<String, String> active(Principal principal) {
        return Map.of("sessionId", chatService.activeSessionId(principal.getName()));
    }

    /**
     * Devuelve métricas vivas del motor de decisión RAG para la home.
     */
    @GetMapping("/rag/metrics")
    public ChatRagTelemetrySnapshotDto ragMetrics(Principal principal) {
        return chatService.ragTelemetry();
    }

    /**
     * Crea una nueva sesion generica desligada de integraciones externas.
     */
    @PostMapping("/sessions")
    public Map<String, String> newSession(Principal principal) {
        return Map.of("sessionId", chatService.newSession(principal.getName()));
    }

    /**
     * Lista las sesiones visibles para el usuario web.
     */
    @GetMapping("/sessions")
    public List<SessionSummaryDto> listSessions(Principal principal) {
        return chatService.listSessions(principal.getName());
    }

    /**
     * Marca una sesion como activa actualizando su ultima actividad.
     */
    @PutMapping("/sessions/{sessionId}/activate")
    public Map<String, String> activate(@PathVariable String sessionId, Principal principal) {
        return Map.of("sessionId", chatService.activateSession(principal.getName(), sessionId));
    }

    /**
     * Renombra una sesion existente con un titulo manual validado.
     */
    @PutMapping("/sessions/{sessionId}/title")
    public Map<String, String> rename(@PathVariable String sessionId,
                                      @Valid @RequestBody RenameSessionRequest req,
                                      Principal principal) {
        chatService.renameSession(principal.getName(), sessionId, req.getTitle());
        return Map.of("ok", "true");
    }

    /**
     * Elimina una sesion generica del usuario y todo su historial asociado.
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, String> delete(@PathVariable String sessionId, Principal principal) {
        chatService.deleteSession(principal.getName(), sessionId);
        return Map.of("ok", "true");
    }

    /**
     * Elimina todas las sesiones genericas del usuario.
     */
    @DeleteMapping("/sessions")
    public Map<String, String> deleteAll(Principal principal) {
        int deleted = chatService.deleteAllSessions(principal.getName());
        return Map.of("deleted", String.valueOf(deleted));
    }

    /**
     * Entra al flujo principal de chat web.
     * La cola serializa turnos por sesion para evitar carreras entre mensajes consecutivos.
     */
    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest req, Principal principal) {
        return chatQueueService.chatAndWait(
                principal.getName(),
                req.getSessionId(),
                req.getMessage(),
                req.getModel(),
                null,
                req.getMedia()
        );
    }

    /**
     * Devuelve el historial completo de una sesion generica del usuario.
     */
    @GetMapping("/{sessionId}/history")
    public List<ChatMessageDto> history(@PathVariable String sessionId, Principal principal) {
        return chatService.historyDto(principal.getName(), sessionId);
    }
}



