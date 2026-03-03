// repository/ChatMessageRepository.java
package com.example.apiasistente.chat.repository;

import com.example.apiasistente.chat.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Repositorio para Chat Message.
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findTop50BySession_IdOrderByCreatedAtDesc(String sessionId);
    List<ChatMessage> findBySession_IdOrderByCreatedAtAsc(String sessionId);
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    @Query("""
        select m
        from ChatMessage m
        where m.session.id = :sessionId
          and (:excludeMessageId is null or m.id <> :excludeMessageId)
        order by m.createdAt desc
    """)
    List<ChatMessage> findRecentForContext(@Param("sessionId") String sessionId,
                                           @Param("excludeMessageId") Long excludeMessageId,
                                           Pageable pageable);

    @Query("""
        select m
        from ChatMessage m
        where m.session.id = :sessionId
          and m.role = :role
        order by m.createdAt desc
    """)
    List<ChatMessage> findRecentBySessionAndRole(@Param("sessionId") String sessionId,
                                                 @Param("role") ChatMessage.Role role,
                                                 Pageable pageable);

}


