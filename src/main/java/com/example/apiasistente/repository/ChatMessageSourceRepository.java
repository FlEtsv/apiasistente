// repository/ChatMessageSourceRepository.java
package com.example.apiasistente.repository;

import com.example.apiasistente.model.entity.ChatMessageSource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageSourceRepository extends JpaRepository<ChatMessageSource, Long> {}
