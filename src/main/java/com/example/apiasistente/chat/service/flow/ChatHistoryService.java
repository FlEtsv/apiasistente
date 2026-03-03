package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.dto.ChatMessageDto;
import com.example.apiasistente.chat.entity.ChatMessage;
import com.example.apiasistente.chat.entity.ChatMessageSource;
import com.example.apiasistente.chat.entity.ChatSession;
import com.example.apiasistente.rag.service.RagService;
import com.example.apiasistente.chat.repository.ChatMessageRepository;
import com.example.apiasistente.chat.repository.ChatMessageSourceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Encapsula persistencia y lectura del historial de chat.
 * Tambien gestiona el enlace entre respuestas del asistente y chunks usados como fuentes.
 */
@Service
public class ChatHistoryService {

    private final ChatMessageRepository messageRepo;
    private final ChatMessageSourceRepository sourceRepo;
    private final ChatSessionService sessionService;

    @Value("${rag.max-history:40}")
    private int maxHistory;

    @Value("${rag.retrieval.user-turns:3}")
    private int retrievalUserTurns;

    public ChatHistoryService(ChatMessageRepository messageRepo,
                              ChatMessageSourceRepository sourceRepo,
                              ChatSessionService sessionService) {
        this.messageRepo = messageRepo;
        this.sourceRepo = sourceRepo;
        this.sessionService = sessionService;
    }

    /**
     * Persiste el mensaje del usuario al inicio del turno para que el historial quede consistente.
     */
    public ChatMessage saveUserMessage(ChatSession session, String userText) {
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSession(session);
        userMsg.setRole(ChatMessage.Role.USER);
        userMsg.setContent(userText);
        return messageRepo.save(userMsg);
    }

    /**
     * Persiste la respuesta final del asistente cuando el turno ya termino.
     */
    public ChatMessage saveAssistantMessage(ChatSession session, String assistantText) {
        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setSession(session);
        assistantMsg.setRole(ChatMessage.Role.ASSISTANT);
        assistantMsg.setContent(assistantText);
        return messageRepo.save(assistantMsg);
    }

    /**
     * Devuelve una ventana reciente de historial para inyectarla en el prompt.
     * Puede excluir el mensaje recien persistido del usuario para evitar duplicados.
     */
    public List<ChatMessage> recentHistoryForPrompt(String sessionId, Long excludeMessageId) {
        int historyLimit = Math.max(0, maxHistory);
        if (historyLimit == 0) {
            return List.of();
        }
        return messageRepo.findRecentForContext(
                sessionId,
                excludeMessageId,
                PageRequest.of(0, historyLimit)
        );
    }

    /**
     * Devuelve solo turnos recientes del usuario para enriquecer la query de retrieval.
     */
    public List<ChatMessage> recentUserTurnsForRetrieval(String sessionId) {
        int turns = Math.max(1, retrievalUserTurns);
        return messageRepo.findRecentBySessionAndRole(
                sessionId,
                ChatMessage.Role.USER,
                PageRequest.of(0, turns)
        );
    }

    /**
     * Enlaza la respuesta del asistente con los chunks usados como soporte del turno.
     */
    public void persistSources(ChatMessage assistantMsg, List<RagService.ScoredChunk> scored) {
        if (scored == null || scored.isEmpty()) {
            return;
        }

        // Persistimos todos los chunks devueltos para trazabilidad posterior del turno.
        List<ChatMessageSource> links = new ArrayList<>(scored.size());
        for (RagService.ScoredChunk scoredChunk : scored) {
            if (scoredChunk == null || scoredChunk.chunk() == null || scoredChunk.chunk().getDocument() == null) {
                continue;
            }
            ChatMessageSource link = new ChatMessageSource();
            link.setMessage(assistantMsg);
            link.setSourceChunkId(scoredChunk.chunk().getId());
            link.setSourceDocumentId(scoredChunk.chunk().getDocument().getId());
            link.setSourceDocumentTitle(scoredChunk.chunk().getDocument().getTitle());
            link.setSourceSnippet(scoredChunk.effectiveText());
            link.setScore(scoredChunk.score());
            links.add(link);
        }
        if (!links.isEmpty()) {
            sourceRepo.saveAll(links);
        }
    }

    /**
     * Devuelve el historial serializado para consumo de la API.
     */
    @Transactional(readOnly = true)
    public List<ChatMessageDto> historyDto(String username, String sessionId) {
        sessionService.requireOwnedGenericSession(username, sessionId);
        return messageRepo.findBySession_IdOrderByCreatedAtAsc(sessionId)
                .stream()
                .map(message -> new ChatMessageDto(
                        message.getId(),
                        message.getRole().name(),
                        message.getContent(),
                        message.getCreatedAt()
                ))
                .toList();
    }

    /**
     * Devuelve el historial como entidades para usos internos del backend.
     */
    @Transactional(readOnly = true)
    public List<ChatMessage> historyEntitiesForInternalUse(String username, String sessionId) {
        sessionService.requireOwnedGenericSession(username, sessionId);
        return messageRepo.findBySession_IdOrderByCreatedAtAsc(sessionId);
    }
}


