package com.example.apiasistente.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Set;
import java.util.stream.Collectors;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return "redirect:/login";
        }

        Set<String> auths = authentication.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        if (auths.contains("PERM_CHAT")) {
            return "redirect:/chat";
        }
        if (auths.contains("PERM_MONITOR")) {
            return "redirect:/monitor";
        }
        if (auths.contains("PERM_RAG")) {
            return "redirect:/rag-admin";
        }
        return "redirect:/access-denied";
    }

    @GetMapping("/access-denied")
    public String accessDenied() {
        return "access_denied";
    }
}
