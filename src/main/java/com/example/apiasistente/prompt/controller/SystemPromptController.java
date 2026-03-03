package com.example.apiasistente.prompt.controller;

import com.example.apiasistente.prompt.repository.SystemPromptRepository;
import com.example.apiasistente.prompt.service.SystemPromptService;
import org.springframework.web.bind.annotation.*;

/**
 * Controlador para System Prompt.
 */
@RestController
@RequestMapping("/api/system-prompts")
public class SystemPromptController {

    private final SystemPromptRepository repo;
    private final SystemPromptService service;

    public SystemPromptController(SystemPromptRepository repo, SystemPromptService service) {
        this.repo = repo;
        this.service = service;
    }

    @GetMapping
    public Object list() {
        return repo.findAll();
    }

    @PutMapping("/{id}/active")
    public Object setActive(@PathVariable Long id) {
        service.setActive(id);
        return java.util.Map.of("activeId", id);
    }
}


