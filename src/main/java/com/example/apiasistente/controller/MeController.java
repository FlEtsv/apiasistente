// MeController.java
package com.example.apiasistente.controller;

import com.example.apiasistente.service.ApiKeyService;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/me")
public class MeController {

    private final ApiKeyService apiKeyService;

    public MeController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping("/api-key")
    public Map<String, String> rotate(Principal principal) {
        String key = apiKeyService.rotateKey(principal.getName());
        return Map.of("apiKey", key);
    }
}
