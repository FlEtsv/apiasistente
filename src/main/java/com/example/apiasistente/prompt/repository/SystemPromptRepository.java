// repository/SystemPromptRepository.java
package com.example.apiasistente.prompt.repository;

import com.example.apiasistente.prompt.entity.SystemPrompt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repositorio para System Prompt.
 */
public interface SystemPromptRepository extends JpaRepository<SystemPrompt, Long> {
    Optional<SystemPrompt> findFirstByActiveTrue();
}


