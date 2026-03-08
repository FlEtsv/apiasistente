package com.example.apiasistente.chat.controller;

import com.example.apiasistente.chat.service.ChatRuntimeAdaptationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * Expone el perfil runtime usado por el autotuning de chat.
 */
@RestController
@RequestMapping("/api/chat/runtime")
public class ChatRuntimeController {

    private final ChatRuntimeAdaptationService runtimeAdaptationService;

    public ChatRuntimeController(ChatRuntimeAdaptationService runtimeAdaptationService) {
        this.runtimeAdaptationService = runtimeAdaptationService;
    }

    @GetMapping("/profile")
    public ChatRuntimeAdaptationService.RuntimeProfile profile(Principal principal) {
        return runtimeAdaptationService.currentProfile();
    }
}
