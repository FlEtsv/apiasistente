package com.example.apiasistente.repository;

import com.example.apiasistente.model.dto.SessionDetailsDto;
import com.example.apiasistente.model.dto.SessionSummaryDto;
import com.example.apiasistente.model.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

    Optional<ChatSession> findFirstByUser_IdOrderByLastActivityAtDesc(Long userId);

    Optional<ChatSession> findByIdAndUser_Id(String id, Long userId);

    List<ChatSession> findByUser_Id(Long userId);

    @Query("""
        select new com.example.apiasistente.model.dto.SessionSummaryDto(
            s.id,
            s.title,
            s.createdAt,
            s.lastActivityAt,
            count(m.id),
            max(m.createdAt)
        )
        from ChatSession s
        left join com.example.apiasistente.model.entity.ChatMessage m
            on m.session = s
        where s.user.id = :userId
        group by s.id, s.title, s.createdAt, s.lastActivityAt
        order by s.lastActivityAt desc
    """)
    List<SessionSummaryDto> listSummaries(@Param("userId") Long userId);

    @Query("""
        select new com.example.apiasistente.model.dto.SessionDetailsDto(
            s.id,
            s.title,
            s.createdAt,
            s.lastActivityAt,
            count(m.id),
            max(m.createdAt)
        )
        from ChatSession s
        left join com.example.apiasistente.model.entity.ChatMessage m
            on m.session = s
        where s.user.id = :userId and s.id = :sessionId
        group by s.id, s.title, s.createdAt, s.lastActivityAt
    """)
    Optional<SessionDetailsDto> findDetails(@Param("userId") Long userId, @Param("sessionId") String sessionId);
}
