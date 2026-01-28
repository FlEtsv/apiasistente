package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.ChatMessageDto;
import com.example.apiasistente.model.dto.ChatRequest;
import com.example.apiasistente.model.dto.ChatResponse;
import com.example.apiasistente.model.dto.RenameSessionRequest;
import com.example.apiasistente.model.dto.SessionSummaryDto;
import com.example.apiasistente.service.ChatQueueService;
import com.example.apiasistente.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatApiController {

    private final ChatService chatService;
    private final ChatQueueService chatQueueService;

    public ChatApiController(ChatService chatService, ChatQueueService chatQueueService) {
        this.chatService = chatService;
        this.chatQueueService = chatQueueService;
    }

    // ---------------------------------------------------------------------
    // SESIÓN “ACTIVA”
    // Devuelve el ID de la última sesión usada. Si no existe, crea una nueva.
    // ---------------------------------------------------------------------
    @GetMapping("/active")
    public Map<String, String> active(Principal principal) {
        String sid = chatService.activeSessionId(principal.getName());
        return Map.of("sessionId", sid);
    }

    // ---------------------------------------------------------------------
    // CREAR CHAT NUEVO
    // Crea una sesión nueva y la devuelve como activa.
    // ---------------------------------------------------------------------
    @PostMapping("/sessions")
    public Map<String, String> newSession(Principal principal) {
        String sid = chatService.newSession(principal.getName());
        return Map.of("sessionId", sid);
    }

    // ---------------------------------------------------------------------
    // LISTAR CHATS DEL USUARIO
    // Devuelve resúmenes (id, título, fechas, etc.), NUNCA entidades.
    // ---------------------------------------------------------------------
    @GetMapping("/sessions")
    public List<SessionSummaryDto> listSessions(Principal principal) {
        return chatService.listSessions(principal.getName());
    }

    // ---------------------------------------------------------------------
    // ACTIVAR CHAT
    // Marca el chat como último usado.
    // OJO: esto sirve para que /active devuelva este chat.
    // ---------------------------------------------------------------------
    @PutMapping("/sessions/{sessionId}/activate")
    public Map<String, String> activate(@PathVariable String sessionId, Principal principal) {
        String sid = chatService.activateSession(principal.getName(), sessionId);
        return Map.of("sessionId", sid);
    }

    // ---------------------------------------------------------------------
    // RENOMBRAR CHAT
    // Cambia el título de la sesión.
    // ---------------------------------------------------------------------
    @PutMapping("/sessions/{sessionId}/title")
    public Map<String, String> rename(@PathVariable String sessionId,
                                      @Valid @RequestBody RenameSessionRequest req,
                                      Principal principal) {
        chatService.renameSession(principal.getName(), sessionId, req.getTitle());
        return Map.of("ok", "true");
    }

    // ---------------------------------------------------------------------
    // BORRAR CHAT
    // Borra sesión y mensajes (si tu cascade está bien configurado).
    // ---------------------------------------------------------------------
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, String> delete(@PathVariable String sessionId, Principal principal) {
        chatService.deleteSession(principal.getName(), sessionId);
        return Map.of("ok", "true");
    }

    // ---------------------------------------------------------------------
    // CHAT NORMAL
    // Envía mensaje y devuelve respuesta + fuentes RAG.
    // ---------------------------------------------------------------------
    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest req, Principal principal) {
        return chatQueueService.chatAndWait(
                principal.getName(),
                req.getSessionId(),
                req.getMessage(),
                req.getModel()
        );
    }

    // ---------------------------------------------------------------------
    // HISTORIAL (FIX CRÍTICO)
    // Devuelve DTOs (NO ENTIDADES) para evitar LazyInitializationException.
    // ---------------------------------------------------------------------
    @GetMapping("/{sessionId}/history")
    public List<ChatMessageDto> history(@PathVariable String sessionId, Principal principal) {
        return chatService.historyDto(principal.getName(), sessionId);
    }
}
