package com.example.apiasistente.service;

import com.example.apiasistente.model.dto.*;
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

    private static final String DEFAULT_TITLE = "Nuevo chat";
    private static final int MANUAL_TITLE_MAX_LENGTH = 120;
    private static final int AUTO_TITLE_MAX_LENGTH = 60;

    private final ChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final ChatMessageSourceRepository sourceRepo;
    private final SystemPromptService promptService;
    private final RagService ragService;
    private final OllamaClient ollama;
    private final AppUserRepository userRepo;

    /**
     * Cuántos mensajes anteriores metemos en el contexto del modelo.
     * Tú has subido esto a 40: perfecto, pero controla coste / latencia.
     *
     * En application.properties:
     * rag.max-history=40
     */
    @Value("${rag.max-history:40}")
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

    // =========================================================================
    // CHAT
    // =========================================================================

    /**
     * Flujo completo:
     * 1) comprobar usuario
     * 2) resolver sesión (la que viene o la última del usuario o crear)
     * 3) guardar mensaje usuario
     * 4) RAG retrieve
     * 5) construir contexto para Ollama (system + history + rag)
     * 6) llamar al modelo
     * 7) guardar respuesta + fuentes
     */
    @Transactional
    public ChatResponse chat(String username, String maybeSessionId, String userText) {

        // 1) Usuario autenticado debe existir en BD
        AppUser user = requireUser(username);

        // 2) Resolver sesión (o validar que sea suya)
        ChatSession session = resolveSession(user, maybeSessionId);

        // 3) actualizar "última actividad" y título automático si procede
        touchSession(session);
        autoTitleIfDefault(session, userText);

        // 4) Guardar mensaje del usuario
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSession(session);
        userMsg.setRole(ChatMessage.Role.USER);
        userMsg.setContent(userText);
        messageRepo.save(userMsg);

        // 5) RAG: recuperar chunks relevantes
        var scored = ragService.retrieveTopKForOwnerOrGlobal(userText, username);
        List<SourceDto> sources = ragService.toSourceDtos(scored);

        // 6) Construir mensajes para Ollama
        List<OllamaClient.Message> msgs = new ArrayList<>();

        // prompt del sistema asociado a la sesión
        SystemPrompt prompt = session.getSystemPrompt();
        msgs.add(new OllamaClient.Message("system", prompt.getContent()));

        // histórico (últimos N)
        var historyDesc = messageRepo.findTop50BySession_IdOrderByCreatedAtDesc(session.getId());
        historyDesc.stream()
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt))
                .skip(Math.max(0, historyDesc.size() - maxHistory))
                .forEach(m -> msgs.add(new OllamaClient.Message(
                        m.getRole() == ChatMessage.Role.USER ? "user" : "assistant",
                        m.getContent()
                )));

        // bloque RAG + pregunta del usuario
        msgs.add(new OllamaClient.Message("user", buildRagBlock(userText, scored)));

        // 7) Llamada al modelo
        String assistantText = ollama.chat(msgs);

        // 8) Guardar respuesta del asistente
        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setSession(session);
        assistantMsg.setRole(ChatMessage.Role.ASSISTANT);
        assistantMsg.setContent(assistantText);
        assistantMsg = messageRepo.save(assistantMsg);

        // 9) Log de fuentes (relación mensaje -> chunks)
        for (var sc : scored) {
            ChatMessageSource link = new ChatMessageSource();
            link.setMessage(assistantMsg);
            link.setChunk(sc.chunk());
            link.setScore(sc.score());
            sourceRepo.save(link);
        }

        // 10) actualizar actividad
        touchSession(session);

        return new ChatResponse(session.getId(), assistantText, sources);
    }

    // =========================================================================
    // HISTORIAL (FIX CRÍTICO)
    // =========================================================================

    /**
     * NUNCA devuelvas entidades JPA al frontend.
     * Esto evita LazyInitializationException y evita exponer relaciones internas.
     */
    @Transactional(readOnly = true)
    public List<ChatMessageDto> historyDto(String username, String sessionId) {
        AppUser user = requireUser(username);

        // valida propiedad y devuelve la sesión por si la necesitas
        requireOwnedSession(user, sessionId);

        // cargamos mensajes como entidades, pero devolvemos DTOs (sin session/user)
        return messageRepo.findBySession_IdOrderByCreatedAtAsc(sessionId)
                .stream()
                .map(m -> new ChatMessageDto(
                        m.getId(),
                        m.getRole().name(),
                        m.getContent(),
                        m.getCreatedAt()
                ))
                .toList();
    }

    /**
     * Si quieres mantener esto para lógica interna, ok,
     * pero NO lo expongas como JSON.
     */
    @Transactional(readOnly = true)
    public List<ChatMessage> historyEntitiesForInternalUse(String username, String sessionId) {
        AppUser user = requireUser(username);
        requireOwnedSession(user, sessionId);
        return messageRepo.findBySession_IdOrderByCreatedAtAsc(sessionId);
    }

    // =========================================================================
    // SESIÓN ACTIVA / CREAR NUEVA
    // =========================================================================

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

    // =========================================================================
    // SESIONES (LIST/ACTIVATE/RENAME/DELETE)
    // =========================================================================

    public List<SessionSummaryDto> listSessions(String username) {
        AppUser user = requireUser(username);
        return sessionRepo.listSummaries(user.getId());
    }

    @Transactional
    public String activateSession(String username, String sessionId) {
        AppUser user = requireUser(username);

        ChatSession s = requireOwnedSession(user, sessionId);
        touchSession(s);
        return s.getId();
    }

    @Transactional
    public void renameSession(String username, String sessionId, String title) {
        AppUser user = requireUser(username);

        ChatSession s = requireOwnedSession(user, sessionId);
        s.setTitle(normalizeManualTitle(title));
        touchSession(s);
        sessionRepo.save(s);
    }

    @Transactional
    public void deleteSession(String username, String sessionId) {
        AppUser user = requireUser(username);

        ChatSession s = requireOwnedSession(user, sessionId);
        // Si tu cascade REMOVE está bien, esto borra todo el chat
        sessionRepo.delete(s);
    }

    // =========================================================================
    // HELPERS (seguridad + creación + títulos)
    // =========================================================================

    private AppUser requireUser(String username) {
        return userRepo.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Usuario autenticado no existe en BD: " + username));
    }

    /**
     * Resuelve una sesión:
     * - si viene sessionId -> valida que exista y sea del usuario
     * - si no viene -> usa la última sesión del usuario o crea una nueva
     */
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

    /**
     * Valida que la sesión exista y sea del usuario.
     * Devuelve la sesión para que puedas reutilizarla si lo necesitas.
     */
    private ChatSession requireOwnedSession(AppUser user, String sessionId) {
        ChatSession s = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Sesión no encontrada: " + sessionId));

        if (!s.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("No puedes acceder a sesiones de otro usuario");
        }
        return s;
    }

    private ChatSession createSession(AppUser user) {
        SystemPrompt active = promptService.activePromptOrThrow();

        ChatSession s = new ChatSession();
        s.setId(UUID.randomUUID().toString());
        s.setUser(user);
        s.setSystemPrompt(active);
        s.setTitle(DEFAULT_TITLE);
        s.setLastActivityAt(Instant.now());

        return sessionRepo.save(s);
    }

    /**
     * Marca sesión como “última actividad”.
     * Importante para ordenar chats y para /active.
     */
    private void touchSession(ChatSession s) {
        s.setLastActivityAt(Instant.now());
        sessionRepo.save(s);
    }

    /**
     * Autotítulo: si el chat está en "Nuevo chat", lo reemplaza por el texto inicial del usuario.
     */
    private void autoTitleIfDefault(ChatSession s, String userText) {
        if (s.getTitle() != null && !s.getTitle().equalsIgnoreCase(DEFAULT_TITLE)) return;

        String t = normalizeAutoTitle(userText);
        if (t.isEmpty()) return;
        s.setTitle(t);
        sessionRepo.save(s);
    }

    private String normalizeManualTitle(String title) {
        String clean = (title == null) ? "" : title.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Título vacío");
        }
        if (clean.length() > MANUAL_TITLE_MAX_LENGTH) {
            clean = clean.substring(0, MANUAL_TITLE_MAX_LENGTH);
        }
        return clean;
    }

    private String normalizeAutoTitle(String text) {
        String t = (text == null ? "" : text.trim());
        if (t.isEmpty()) {
            return "";
        }
        t = t.replaceAll("\\s+", " ");
        if (t.length() > AUTO_TITLE_MAX_LENGTH) {
            t = t.substring(0, AUTO_TITLE_MAX_LENGTH) + "…";
        }
        return t;
    }

    /**
     * Construye el bloque final que se envía al modelo:
     * - contexto RAG (si existe)
     * - instrucción breve
     * - pregunta del usuario
     */
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

    // =========================================================================
    // EXTRA: detalles sesión (si lo usas en frontend, está bien que sea DTO)
    // =========================================================================

    public SessionDetailsDto sessionDetails(String username, String sessionId) {
        AppUser user = requireUser(username);
        return sessionRepo.findDetails(user.getId(), sessionId)
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException(
                        "Sesión no encontrada o no pertenece al usuario"
                ));
    }
}
