package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.ChatRequest;
import com.example.apiasistente.model.dto.ChatResponse;
import com.example.apiasistente.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatApiController {

    private final ChatService chatService;

    public ChatApiController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest req) {
        return chatService.chat(req.getSessionId(), req.getMessage());
    }

    @GetMapping("/{sessionId}/history")
    public Object history(@PathVariable String sessionId) {
        return chatService.history(sessionId);
    }
}
