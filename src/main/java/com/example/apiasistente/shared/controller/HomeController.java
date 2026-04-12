package com.example.apiasistente.shared.controller;

import com.example.apiasistente.setup.service.SetupConfigService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Controlador para Home.
 */
@Controller
public class HomeController {

    private final SetupConfigService setupConfigService;

    public HomeController(SetupConfigService setupConfigService) {
        this.setupConfigService = setupConfigService;
    }

    @GetMapping("/")
    public String home(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return "redirect:/app/login";
        }

        if (!setupConfigService.isConfigured()) {
            return "redirect:/app/setup";
        }

        Set<String> auths = authentication.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        if (auths.contains("PERM_CHAT")) {
            return "redirect:/app/chat";
        }
        if (auths.contains("PERM_MONITOR")) {
            return "redirect:/app/monitor";
        }
        if (auths.contains("PERM_RAG")) {
            return "redirect:/app/rag-admin";
        }
        if (auths.contains("PERM_API_KEYS")) {
            return "redirect:/app/admin";
        }
        return "redirect:/app/access-denied";
    }

    @GetMapping("/access-denied")
    public String accessDenied() {
        return "redirect:/app/access-denied";
    }
}

