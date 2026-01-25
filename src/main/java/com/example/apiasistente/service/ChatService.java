package com.example.apiasistente.service;

import com.example.apiasistente.model.dto.ChatResponse;
import com.example.apiasistente.model.dto.SessionDetailsDto;
import com.example.apiasistente.model.dto.SessionSummaryDto;
import com.example.apiasistente.model.dto.SourceDto;
import com.example.apiasistente.model.entity.*;
import com.example.apiasistente.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class ChatService {

    private final ChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final ChatMessageSourceRepository sourceRepo;
    private final SystemPromptService promptService;
    private final RagService ragService;
    private final OllamaClient ollama;
    private final AppUserRepository userRepo;

    @Value("${rag.max-history:12}")
    private int maxHistory;

    public ChatService(
            ChatSessionRepository sessionRepo,
            ChatMessageRepository messageRepo,
            ChatMessageSourceRepository sourceRepo,
            SystemPromptService promptService,
            RagService ragService,
            OllamaClient ollama,
            AppUserRepository userRepo
    ) {
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.sourceRepo = sourceRepo;
        this.promptService = promptService;
        this.ragService = ragService;
        this.ollama = ollama;
        this.userRepo = userRepo;
    }

    // ----------------- CHAT -----------------

    @Transactional
    public ChatResponse chat(String username, String maybeSessionId, String userText) {
        AppUser user = requireUser(username);
        ChatSession session = resolveSession(user, maybeSessionId);

        // actividad + autotítulo si aún está genérico
        touchSession(session);
        autoTitleIfDefault(session, userText);

        // 1) Guardar mensaje usuario
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSession(session);
        userMsg.setRole(ChatMessage.Role.USER);
        userMsg.setContent(userText);
        messageRepo.save(userMsg);

        // 2) RAG
        var scored = ragService.retrieveTopK(userText);
        List<SourceDto> sources = ragService.toSourceDtos(scored);

        // 3) Mensajes para Ollama
        List<OllamaClient.Message> msgs = new ArrayList<>();
        SystemPrompt prompt = session.getSystemPrompt();
        msgs.add(new OllamaClient.Message("system", prompt.getContent()));

        var historyDesc = messageRepo.findTop20BySession_IdOrderByCreatedAtDesc(session.getId());
        historyDesc.stream()
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt))
                .skip(Math.max(0, historyDesc.size() - maxHistory))
                .forEach(m -> msgs.add(new OllamaClient.Message(
                        m.getRole() == ChatMessage.Role.USER ? "user" : "assistant",
                        m.getContent()
                )));

        msgs.add(new OllamaClient.Message("user", buildRagBlock(userText, scored)));

        // 4) Llamada a Ollama
        String assistantText = ollama.chat(msgs);

        // 5) Guardar respuesta
        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setSession(session);
        assistantMsg.setRole(ChatMessage.Role.ASSISTANT);
        assistantMsg.setContent(assistantText);
        assistantMsg = messageRepo.save(assistantMsg);

        // 6) Log de fuentes
        for (var sc : scored) {
            ChatMessageSource link = new ChatMessageSource();
            link.setMessage(assistantMsg);
            link.setChunk(sc.chunk());
            link.setScore(sc.score());
            sourceRepo.save(link);
        }

        touchSession(session);
        return new ChatResponse(session.getId(), assistantText, sources);
    }

    public List<ChatMessage> history(String username, String sessionId) {
        AppUser user = requireUser(username);
        requireOwnedSession(user, sessionId);
        return messageRepo.findBySession_IdOrderByCreatedAtAsc(sessionId);
    }

    public String activeSessionId(String username) {
        AppUser user = requireUser(username);
        return sessionRepo.findFirstByUser_IdOrderByLastActivityAtDesc(user.getId())
                .map(ChatSession::getId)
                .orElseGet(() -> createSession(user).getId());
    }

    @Transactional
    public String newSession(String username) {
        AppUser user = requireUser(username);
        return createSession(user).getId();
    }

    // ----------------- SESIONES (LIST/ACTIVATE/RENAME/DELETE) -----------------

    public List<SessionSummaryDto> listSessions(String username) {
        AppUser user = requireUser(username);
        return sessionRepo.listSummaries(user.getId());
    }
    @Transactional
    public String activateSession(String username, String sessionId) {
        AppUser user = requireUser(username);
        ChatSession s = sessionRepo.findByIdAndUser_Id(sessionId, user.getId())
                .orElseThrow(() -> new AccessDeniedException("Sesión no encontrada o no pertenece al usuario"));
        touchSession(s);
        return s.getId();
    }

    @Transactional
    public void renameSession(String username, String sessionId, String title) {
        AppUser user = requireUser(username);
        ChatSession s = sessionRepo.findByIdAndUser_Id(sessionId, user.getId())
                .orElseThrow(() -> new AccessDeniedException("Sesión no encontrada o no pertenece al usuario"));

        String clean = (title == null) ? "" : title.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException("Título vacío");
        if (clean.length() > 120) clean = clean.substring(0, 120);

        s.setTitle(clean);
        touchSession(s);
        sessionRepo.save(s);
    }

    @Transactional
    public void deleteSession(String username, String sessionId) {
        AppUser user = requireUser(username);
        ChatSession s = sessionRepo.findByIdAndUser_Id(sessionId, user.getId())
                .orElseThrow(() -> new AccessDeniedException("Sesión no encontrada o no pertenece al usuario"));

        // gracias al cascade REMOVE en ChatSession -> ChatMessage -> ChatMessageSource, esto borra todo el chat
        sessionRepo.delete(s);
    }

    // ----------------- helpers -----------------

    private AppUser requireUser(String username) {
        return userRepo.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Usuario autenticado no existe en BD: " + username));
    }

    private ChatSession resolveSession(AppUser user, String maybeSessionId) {
        if (maybeSessionId != null && !maybeSessionId.isBlank()) {
            ChatSession s = sessionRepo.findById(maybeSessionId)
                    .orElseThrow(() -> new NoSuchElementException("Sesión no encontrada: " + maybeSessionId));
            if (!s.getUser().getId().equals(user.getId())) {
                throw new AccessDeniedException("No puedes acceder a sesiones de otro usuario");
            }
            return s;
        }

        return sessionRepo.findFirstByUser_IdOrderByLastActivityAtDesc(user.getId())
                .orElseGet(() -> createSession(user));
    }

    private void requireOwnedSession(AppUser user, String sessionId) {
        ChatSession s = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Sesión no encontrada: " + sessionId));
        if (!s.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("No puedes acceder a sesiones de otro usuario");
        }
    }

    private ChatSession createSession(AppUser user) {
        SystemPrompt active = promptService.activePromptOrThrow();
        ChatSession s = new ChatSession();
        s.setId(UUID.randomUUID().toString());
        s.setUser(user);
        s.setSystemPrompt(active);
        s.setTitle("Nuevo chat");
        s.setLastActivityAt(Instant.now());
        return sessionRepo.save(s);
    }

    private void touchSession(ChatSession s) {
        s.setLastActivityAt(Instant.now());
        sessionRepo.save(s);
    }

    private void autoTitleIfDefault(ChatSession s, String userText) {
        if (s.getTitle() != null && !s.getTitle().equalsIgnoreCase("Nuevo chat")) return;

        String t = (userText == null ? "" : userText.trim());
        if (t.isEmpty()) return;

        // título: primeras 60 chars
        t = t.replaceAll("\\s+", " ");
        if (t.length() > 60) t = t.substring(0, 60) + "…";
        s.setTitle(t);
        sessionRepo.save(s);
    }

    private String buildRagBlock(String userText, List<RagService.ScoredChunk> scored) {
        StringBuilder sb = new StringBuilder();

        if (scored == null || scored.isEmpty()) {
            sb.append("Contexto RAG: (sin contexto RAG)\n");
        } else {
            sb.append("Contexto RAG (úsalo si es relevante):\n");
            for (int i = 0; i < scored.size(); i++) {
                var c = scored.get(i).chunk();
                sb.append("\n[").append(i + 1).append("] ")
                        .append(c.getDocument().getTitle())
                        .append(" (chunk ").append(c.getChunkIndex()).append(")\n")
                        .append(c.getText()).append("\n");
            }
        }

        sb.append("\n---\n");
        sb.append("Responde de forma clara y directa.\n");
        sb.append("Pregunta del usuario: ").append(userText);

        return sb.toString();
    }


    public SessionDetailsDto sessionDetails(String username, String sessionId) {
        AppUser user = requireUser(username);
        return sessionRepo.findDetails(user.getId(), sessionId)
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException(
                        "Sesión no encontrada o no pertenece al usuario"
                ));
    }
}
