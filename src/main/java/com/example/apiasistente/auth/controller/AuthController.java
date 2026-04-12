package com.example.apiasistente.auth.controller;

import com.example.apiasistente.auth.service.AuthService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * Controlador para Auth.
 */
@Controller
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/register")
    public String registerPage() {
        return "redirect:/app/register";
    }

    @PostMapping("/register")
    public String doRegister(@RequestParam String username,
                             @RequestParam String password,
                             @RequestParam String code) {
        try {
            authService.register(username, password, code);
            return "redirect:/app/login?registered=1";
        } catch (IllegalArgumentException ex) {
            String encoded = UriUtils.encodeQueryParam(ex.getMessage(), StandardCharsets.UTF_8);
            return "redirect:/app/register?error=1&message=" + encoded;
        }
    }

    @GetMapping("/login")
    public String loginPage() {
        return "redirect:/app/login";
    }
}


