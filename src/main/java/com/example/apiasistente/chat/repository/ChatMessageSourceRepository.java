// repository/ChatMessageSourceRepository.java
package com.example.apiasistente.chat.repository;

import com.example.apiasistente.chat.entity.ChatMessageSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * Repositorio para Chat Message Source.
 */
public interface ChatMessageSourceRepository extends JpaRepository<ChatMessageSource, Long> {

    long countBySourceDocumentId(Long documentId);

    @Query("""
        select max(m.createdAt)
        from ChatMessageSource s
        join s.message m
        where s.sourceDocumentId = :documentId
    """)
    Instant findLastUsedAtByDocumentId(@Param("documentId") Long documentId);
}


