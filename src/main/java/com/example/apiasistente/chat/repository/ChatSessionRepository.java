package com.example.apiasistente.chat.repository;

import com.example.apiasistente.chat.dto.SessionDetailsDto;
import com.example.apiasistente.chat.dto.SessionSummaryDto;
import com.example.apiasistente.chat.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio para Chat Session.
 */
public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

    Optional<ChatSession> findFirstByUser_IdOrderByLastActivityAtDesc(Long userId);

    Optional<ChatSession> findFirstByUser_IdAndExternalUserIdOrderByLastActivityAtDesc(Long userId, String externalUserId);

    Optional<ChatSession> findFirstByUser_IdAndExternalUserIdIsNullOrderByLastActivityAtDesc(Long userId);

    Optional<ChatSession> findByIdAndUser_Id(String id, Long userId);

    List<ChatSession> findByUser_Id(Long userId);

    @Query("""
        select new com.example.apiasistente.chat.dto.SessionSummaryDto(
            s.id,
            s.title,
            s.createdAt,
            s.lastActivityAt,
            count(m.id),
            max(m.createdAt)
        )
        from ChatSession s
        left join com.example.apiasistente.chat.entity.ChatMessage m
            on m.session = s
        where s.user.id = :userId
          and s.externalUserId is null
        group by s.id, s.title, s.createdAt, s.lastActivityAt
        order by s.lastActivityAt desc
    """)
    List<SessionSummaryDto> listSummaries(@Param("userId") Long userId);

    @Query("""
        select new com.example.apiasistente.chat.dto.SessionDetailsDto(
            s.id,
            s.title,
            s.createdAt,
            s.lastActivityAt,
            count(m.id),
            max(m.createdAt)
        )
        from ChatSession s
        left join com.example.apiasistente.chat.entity.ChatMessage m
            on m.session = s
        where s.user.id = :userId and s.id = :sessionId and s.externalUserId is null
        group by s.id, s.title, s.createdAt, s.lastActivityAt
    """)
    Optional<SessionDetailsDto> findDetails(@Param("userId") Long userId, @Param("sessionId") String sessionId);
}


