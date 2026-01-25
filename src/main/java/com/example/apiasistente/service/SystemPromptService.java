package com.example.apiasistente.service;

import com.example.apiasistente.model.entity.SystemPrompt;
import com.example.apiasistente.repository.SystemPromptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SystemPromptService {

    private final SystemPromptRepository repo;

    public SystemPromptService(SystemPromptRepository repo) {
        this.repo = repo;
    }

    public SystemPrompt activePromptOrThrow() {
        return repo.findFirstByActiveTrue()
                .orElseThrow(() -> new IllegalStateException("No hay SystemPrompt activo en BD (revisa data.sql)."));
    }

    @Transactional
    public void setActive(Long id) {
        repo.findAll().forEach(p -> { p.setActive(p.getId().equals(id)); repo.save(p); });
    }
}
