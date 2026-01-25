// repository/SystemPromptRepository.java
package com.example.apiasistente.repository;

import com.example.apiasistente.model.entity.SystemPrompt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SystemPromptRepository extends JpaRepository<SystemPrompt, Long> {
    Optional<SystemPrompt> findFirstByActiveTrue();
}
