// repository/ChatMessageRepository.java
package com.example.apiasistente.repository;

import com.example.apiasistente.model.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findTop20BySession_IdOrderByCreatedAtDesc(String sessionId);
    List<ChatMessage> findBySession_IdOrderByCreatedAtAsc(String sessionId);
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);

}
