package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.dto.SessionDetailsDto;
import com.example.apiasistente.chat.dto.SessionSummaryDto;
import com.example.apiasistente.auth.entity.AppUser;
import com.example.apiasistente.chat.entity.ChatSession;
import com.example.apiasistente.prompt.entity.SystemPrompt;
import com.example.apiasistente.auth.repository.AppUserRepository;
import com.example.apiasistente.chat.repository.ChatSessionRepository;
import com.example.apiasistente.prompt.service.SystemPromptService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * Administra el ciclo de vida de las sesiones de chat.
 * Valida ownership, resuelve scopes genericos o externos y mantiene metadata operativa.
 */
@Service
public class ChatSessionService {

    private static final String DEFAULT_TITLE = "Nuevo chat";
    private static final int MANUAL_TITLE_MAX_LENGTH = 120;
    private static final int AUTO_TITLE_MAX_LENGTH = 60;

    private final ChatSessionRepository sessionRepo;
    private final SystemPromptService promptService;
    private final AppUserRepository userRepo;

    public ChatSessionService(ChatSessionRepository sessionRepo,
                              SystemPromptService promptService,
                              AppUserRepository userRepo) {
        this.sessionRepo = sessionRepo;
        this.promptService = promptService;
        this.userRepo = userRepo;
    }

    /**
     * Resuelve el usuario autenticado desde BD y falla si el principal ya no existe.
     */
    public AppUser requireUser(String username) {
        return userRepo.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Usuario autenticado no existe en BD: " + username));
    }

    /**
     * Normaliza el identificador externo para reducir ruido y limitar longitud en scopes aislados.
     */
    public String normalizeExternalUserId(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        String clean = raw.trim();
        if (clean.length() > 160) {
            clean = clean.substring(0, 160);
        }
        return clean;
    }

    /**
     * Resuelve la sesion efectiva del turno.
     * Si el cliente envia `sessionId`, valida ownership y compatibilidad con el scope externo.
     * Si no la envia, reutiliza o crea la ultima sesion compatible.
     */
    public ChatSession resolveSession(AppUser user, String maybeSessionId, String externalUserId) {
        if (hasText(maybeSessionId)) {
            ChatSession session = sessionRepo.findById(maybeSessionId)
                    .orElseThrow(() -> new NoSuchElementException("Sesion no encontrada: " + maybeSessionId));

            // Impide leer o escribir sesiones de otro usuario.
            if (!Objects.equals(session.getUser().getId(), user.getId())) {
                throw new AccessDeniedException("No puedes acceder a sesiones de otro usuario");
            }

            // Impide mezclar una sesion externa con otro externalUserId o con el modo generico.
            if (hasText(externalUserId)) {
                if (!externalUserId.equals(session.getExternalUserId())) {
                    throw new AccessDeniedException("La sesion no corresponde al usuario externo solicitado.");
                }
            } else if (hasText(session.getExternalUserId())) {
                throw new AccessDeniedException("La sesion pertenece a un usuario externo aislado y requiere modo especial.");
            }

            return session;
        }

        // Sin sessionId se reutiliza la ultima sesion compatible para mantener continuidad conversacional.
        if (hasText(externalUserId)) {
            return sessionRepo.findFirstByUser_IdAndExternalUserIdOrderByLastActivityAtDesc(user.getId(), externalUserId)
                    .orElseGet(() -> createSession(user, externalUserId));
        }

        return sessionRepo.findFirstByUser_IdAndExternalUserIdIsNullOrderByLastActivityAtDesc(user.getId())
                .orElseGet(() -> createSession(user, null));
    }

    /**
     * Actualiza la ultima actividad de la sesion para ordenarla como activa reciente.
     */
    public void touchSession(ChatSession session) {
        session.setLastActivityAt(Instant.now());
        sessionRepo.save(session);
    }

    /**
     * Reemplaza el titulo por defecto usando el primer mensaje util del usuario.
     */
    public void autoTitleIfDefault(ChatSession session, String userText) {
        if (session.getTitle() != null && !session.getTitle().equalsIgnoreCase(DEFAULT_TITLE)) {
            return;
        }

        String title = normalizeAutoTitle(userText);
        if (title.isEmpty()) {
            return;
        }
        session.setTitle(title);
        sessionRepo.save(session);
    }

    /**
     * Resuelve una sesion generica por usuario y valida ownership.
     */
    @Transactional(readOnly = true)
    public ChatSession requireOwnedGenericSession(String username, String sessionId) {
        AppUser user = requireUser(username);
        return requireOwnedGenericSession(user, sessionId);
    }

    /**
     * Variante interna de validacion para una sesion generica ya con usuario cargado.
     */
    @Transactional(readOnly = true)
    public ChatSession requireOwnedGenericSession(AppUser user, String sessionId) {
        ChatSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Sesion no encontrada: " + sessionId));

        // La UI generica nunca debe operar sobre sesiones aisladas de integraciones externas.
        if (!Objects.equals(session.getUser().getId(), user.getId())) {
            throw new AccessDeniedException("No puedes acceder a sesiones de otro usuario");
        }
        if (hasText(session.getExternalUserId())) {
            throw new AccessDeniedException("La sesion pertenece a un usuario externo aislado y no esta disponible en modo generico.");
        }
        return session;
    }

    /**
     * Devuelve la sesion generica activa del usuario o crea una si no tiene ninguna.
     */
    public String activeSessionId(String username) {
        AppUser user = requireUser(username);
        return sessionRepo.findFirstByUser_IdAndExternalUserIdIsNullOrderByLastActivityAtDesc(user.getId())
                .map(ChatSession::getId)
                .orElseGet(() -> createSession(user).getId());
    }

    /**
     * Crea una nueva sesion generica.
     */
    @Transactional
    public String newSession(String username) {
        AppUser user = requireUser(username);
        return createSession(user).getId();
    }

    /**
     * Lista el resumen de sesiones visibles para el usuario.
     */
    @Transactional(readOnly = true)
    public List<SessionSummaryDto> listSessions(String username) {
        AppUser user = requireUser(username);
        return sessionRepo.listSummaries(user.getId());
    }

    /**
     * Reactiva una sesion existente tocando su timestamp de actividad.
     */
    @Transactional
    public String activateSession(String username, String sessionId) {
        AppUser user = requireUser(username);
        ChatSession session = requireOwnedGenericSession(user, sessionId);
        touchSession(session);
        return session.getId();
    }

    /**
     * Aplica un titulo manual validado a la sesion.
     */
    @Transactional
    public void renameSession(String username, String sessionId, String title) {
        AppUser user = requireUser(username);
        ChatSession session = requireOwnedGenericSession(user, sessionId);
        session.setTitle(normalizeManualTitle(title));
        touchSession(session);
        sessionRepo.save(session);
    }

    /**
     * Elimina una sesion generica concreta.
     */
    @Transactional
    public void deleteSession(String username, String sessionId) {
        AppUser user = requireUser(username);
        ChatSession session = requireOwnedGenericSession(user, sessionId);
        sessionRepo.delete(session);
    }

    /**
     * Elimina todas las sesiones del usuario y devuelve cuantas fueron borradas.
     */
    @Transactional
    public int deleteAllSessions(String username) {
        AppUser user = requireUser(username);
        List<ChatSession> sessions = sessionRepo.findByUser_IdAndExternalUserIdIsNull(user.getId());
        if (sessions.isEmpty()) {
            return 0;
        }
        sessionRepo.deleteAll(sessions);
        return sessions.size();
    }

    /**
     * Devuelve metadatos detallados de una sesion del usuario.
     */
    @Transactional(readOnly = true)
    public SessionDetailsDto sessionDetails(String username, String sessionId) {
        AppUser user = requireUser(username);
        return sessionRepo.findDetails(user.getId(), sessionId)
                .orElseThrow(() -> new AccessDeniedException(
                        "Sesion no encontrada o no pertenece al usuario"
                ));
    }

    /**
     * Crea una sesion generica con el prompt activo actual.
     */
    private ChatSession createSession(AppUser user) {
        return createSession(user, null);
    }

    /**
     * Crea una sesion persistida para un scope generico o externo concreto.
     */
    private ChatSession createSession(AppUser user, String externalUserId) {
        SystemPrompt activePrompt = promptService.activePromptOrThrow();

        // Se fija el prompt activo al momento de crear la sesion para que el historial sea consistente.
        ChatSession session = new ChatSession();
        session.setId(UUID.randomUUID().toString());
        session.setUser(user);
        session.setSystemPrompt(activePrompt);
        session.setTitle(DEFAULT_TITLE);
        session.setLastActivityAt(Instant.now());
        session.setExternalUserId(externalUserId);

        return sessionRepo.save(session);
    }

    /**
     * Normaliza un titulo manual preservando longitud maxima y validando que no quede vacio.
     */
    private String normalizeManualTitle(String title) {
        String clean = title == null ? "" : title.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Titulo vacio");
        }
        if (clean.length() > MANUAL_TITLE_MAX_LENGTH) {
            clean = clean.substring(0, MANUAL_TITLE_MAX_LENGTH);
        }
        return clean;
    }

    /**
     * Genera un titulo automatico corto a partir del primer mensaje del usuario.
     */
    private String normalizeAutoTitle(String text) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) {
            return "";
        }
        clean = clean.replaceAll("\\s+", " ");
        if (clean.length() > AUTO_TITLE_MAX_LENGTH) {
            clean = clean.substring(0, AUTO_TITLE_MAX_LENGTH) + "...";
        }
        return clean;
    }

    /**
     * Ayuda local para validar texto util.
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}


