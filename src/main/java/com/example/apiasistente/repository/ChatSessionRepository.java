// repository/ChatSessionRepository.java
package com.example.apiasistente.repository;

import com.example.apiasistente.model.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {}
