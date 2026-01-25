package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.ChatRequest;
import com.example.apiasistente.model.dto.ChatResponse;
import com.example.apiasistente.model.dto.RenameSessionRequest;
import com.example.apiasistente.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatApiController {

    private final ChatService chatService;

    public ChatApiController(ChatService chatService) {
        this.chatService = chatService;
    }

    // Sesión “activa” por usuario
    @GetMapping("/active")
    public Map<String, String> active(Principal principal) {
        String sid = chatService.activeSessionId(principal.getName());
        return Map.of("sessionId", sid);
    }

    // Crear chat nuevo
    @PostMapping("/sessions")
    public Map<String, String> newSession(Principal principal) {
        String sid = chatService.newSession(principal.getName());
        return Map.of("sessionId", sid);
    }

    // Listar chats del usuario
    @GetMapping("/sessions")
    public Object listSessions(Principal principal) {
        return chatService.listSessions(principal.getName());
    }

    // Activar chat (lo pone como “último usado”)
    @PutMapping("/sessions/{sessionId}/activate")
    public Map<String, String> activate(@PathVariable String sessionId, Principal principal) {
        String sid = chatService.activateSession(principal.getName(), sessionId);
        return Map.of("sessionId", sid);
    }

    // Renombrar chat
    @PutMapping("/sessions/{sessionId}/title")
    public Map<String, String> rename(@PathVariable String sessionId,
                                      @Valid @RequestBody RenameSessionRequest req,
                                      Principal principal) {
        chatService.renameSession(principal.getName(), sessionId, req.getTitle());
        return Map.of("ok", "true");
    }

    // Borrar chat
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, String> delete(@PathVariable String sessionId, Principal principal) {
        chatService.deleteSession(principal.getName(), sessionId);
        return Map.of("ok", "true");
    }

    // Chat normal
    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest req, Principal principal) {
        return chatService.chat(principal.getName(), req.getSessionId(), req.getMessage());
    }

    @GetMapping("/{sessionId}/history")
    public Object history(@PathVariable String sessionId, Principal principal) {
        return chatService.history(principal.getName(), sessionId);
    }
    @GetMapping("/sessions/{sessionId}")
    public Object details(@PathVariable String sessionId, Principal principal) {
        return chatService.sessionDetails(principal.getName(), sessionId);
    }

}
