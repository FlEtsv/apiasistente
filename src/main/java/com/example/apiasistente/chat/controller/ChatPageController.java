package com.example.apiasistente.chat.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controlador para Chat Page.
 */
@Controller
public class ChatPageController {

    @GetMapping("/chat")
    public String chatPage() {
        return "chat";
    }
}

