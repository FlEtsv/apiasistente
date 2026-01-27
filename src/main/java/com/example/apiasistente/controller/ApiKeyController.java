package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.*;
import com.example.apiasistente.service.ApiKeyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @GetMapping
    public List<ApiKeyDto> listMine(Principal principal) {
        return apiKeyService.listMine(principal.getName());
    }

    @PostMapping
    public ApiKeyCreateResponse create(@Valid @RequestBody ApiKeyCreateRequest req, Principal principal) {
        return apiKeyService.createForUser(principal.getName(), req.getLabel());
    }

    @DeleteMapping("/{id}")
    public Map<String, String> revoke(@PathVariable Long id, Principal principal) {
        apiKeyService.revokeMine(principal.getName(), id);
        return Map.of("ok", "true");
    }
}
