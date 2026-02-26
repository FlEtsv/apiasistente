package com.example.apiasistente.service;

import com.example.apiasistente.model.dto.ChatMediaInput;
import com.example.apiasistente.model.dto.ChatMessageDto;
import com.example.apiasistente.model.dto.ChatResponse;
import com.example.apiasistente.model.dto.SessionDetailsDto;
import com.example.apiasistente.model.dto.SessionSummaryDto;
import com.example.apiasistente.model.dto.SourceDto;
import com.example.apiasistente.model.entity.AppUser;
import com.example.apiasistente.model.entity.ChatMessage;
import com.example.apiasistente.model.entity.ChatMessageSource;
import com.example.apiasistente.model.entity.ChatSession;
import com.example.apiasistente.model.entity.SystemPrompt;
import com.example.apiasistente.repository.AppUserRepository;
import com.example.apiasistente.repository.ChatMessageRepository;
import com.example.apiasistente.repository.ChatMessageSourceRepository;
import com.example.apiasistente.repository.ChatSessionRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String DEFAULT_TITLE = "Nuevo chat";
    private static final int MANUAL_TITLE_MAX_LENGTH = 120;
    private static final int AUTO_TITLE_MAX_LENGTH = 60;

    private static final int MAX_MEDIA_ITEMS = 4;
    private static final int MAX_MEDIA_TEXT_CHARS = 18_000;
    private static final int MAX_VISUAL_CONTEXT_CHARS = 3_200;
    private static final int MAX_RETRIEVAL_MEDIA_SNIPPET = 420;
    private static final int MAX_GUARD_QUESTION_CHARS = 1_200;
    private static final int MAX_GUARD_ANSWER_CHARS = 10_000;
    private static final int MAX_GUARD_SOURCE_HINTS = 3;

    private final ChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final ChatMessageSourceRepository sourceRepo;
    private final SystemPromptService promptService;
    private final RagService ragService;
    private final OllamaClient ollama;
    private final AppUserRepository userRepo;
    private final ChatModelSelector modelSelector;

    @Value("${rag.max-history:40}")
    private int maxHistory;

    @Value("${rag.retrieval.user-turns:3}")
    private int retrievalUserTurns;

    @Value("${chat.response-guard.enabled:true}")
    private boolean responseGuardEnabled;

    @Value("${chat.response-guard.min-answer-chars:260}")
    private int responseGuardMinAnswerChars;

    @Value("${chat.response-guard.strict-mode:false}")
    private boolean responseGuardStrictMode;

    public ChatService(
            ChatSessionRepository sessionRepo,
            ChatMessageRepository messageRepo,
            ChatMessageSourceRepository sourceRepo,
            SystemPromptService promptService,
            RagService ragService,
            OllamaClient ollama,
            AppUserRepository userRepo,
            ChatModelSelector modelSelector
    ) {
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.sourceRepo = sourceRepo;
        this.promptService = promptService;
        this.ragService = ragService;
        this.ollama = ollama;
        this.userRepo = userRepo;
        this.modelSelector = modelSelector;
    }

    // =========================================================================
    // CHAT
    // =========================================================================

    @Transactional
    public ChatResponse chat(String username, String maybeSessionId, String userText) {
        return chat(username, maybeSessionId, userText, null, null, List.of());
    }

    @Transactional
    public ChatResponse chat(String username, String maybeSessionId, String userText, String requestedModel) {
        return chat(username, maybeSessionId, userText, requestedModel, null, List.of());
    }

    @Transactional
    public ChatResponse chat(String username,
                             String maybeSessionId,
                             String userText,
                             String requestedModel,
                             List<ChatMediaInput> media) {
        return chat(username, maybeSessionId, userText, requestedModel, null, media);
    }

    @Transactional
    public ChatResponse chat(String username,
                             String maybeSessionId,
                             String userText,
                             String requestedModel,
                             String externalUserId) {
        return chat(username, maybeSessionId, userText, requestedModel, externalUserId, List.of());
    }

    @Transactional
    public ChatResponse chat(String username,
                             String maybeSessionId,
                             String userText,
                             String requestedModel,
                             String externalUserId,
                             List<ChatMediaInput> media) {

        String normalizedExternalUserId = normalizeExternalUserId(externalUserId);
        List<PreparedMedia> preparedMedia = prepareMedia(media);

        AppUser user = requireUser(username);
        ChatSession session = resolveSession(user, maybeSessionId, normalizedExternalUserId);

        touchSession(session);
        autoTitleIfDefault(session, userText);

        ChatMessage userMsg = new ChatMessage();
        userMsg.setSession(session);
        userMsg.setRole(ChatMessage.Role.USER);
        userMsg.setContent(userText);
        userMsg = messageRepo.save(userMsg);

        String retrievalQuery = buildRetrievalQuery(session.getId(), userText, preparedMedia);
        var scored = hasText(normalizedExternalUserId)
                ? ragService.retrieveTopKForOwnerScopedAndGlobal(retrievalQuery, username, normalizedExternalUserId)
                : ragService.retrieveTopKForOwnerOrGlobal(retrievalQuery, username);
        List<SourceDto> sources = ragService.toSourceDtos(scored);
        boolean hasRagContext = hasRagContext(scored);
        boolean complexQuery = ChatPromptSignals.isComplexQuery(userText);
        boolean multiStepQuery = ChatPromptSignals.isMultiStepQuery(userText);

        List<OllamaClient.Message> msgs = new ArrayList<>();
        SystemPrompt prompt = session.getSystemPrompt();
        msgs.add(new OllamaClient.Message("system", prompt.getContent()));

        appendRecentHistory(msgs, session.getId(), userMsg.getId());

        String visualBridge = buildVisualBridgeContext(userText, preparedMedia, requestedModel);
        msgs.add(new OllamaClient.Message("user", buildRagBlock(userText, scored, visualBridge, preparedMedia)));

        String model = modelSelector.resolveChatModel(requestedModel, hasRagContext, complexQuery, multiStepQuery);
        if (log.isDebugEnabled()) {
            log.debug(
                    "Model routing selected='{}' requested='{}' rag={} complex={} multiStep={}",
                    model,
                    requestedModel,
                    hasRagContext,
                    complexQuery,
                    multiStepQuery
            );
        }
        String assistantText = ollama.chat(msgs, model);
        assistantText = applyResponseGuard(userText, assistantText, scored);

        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setSession(session);
        assistantMsg.setRole(ChatMessage.Role.ASSISTANT);
        assistantMsg.setContent(assistantText);
        assistantMsg = messageRepo.save(assistantMsg);

        persistSources(assistantMsg, scored);

        touchSession(session);
        return new ChatResponse(session.getId(), assistantText, sources);
    }

    // =========================================================================
    // HISTORIAL
    // =========================================================================

    @Transactional(readOnly = true)
    public List<ChatMessageDto> historyDto(String username, String sessionId) {
        AppUser user = requireUser(username);
        requireOwnedSession(user, sessionId);

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

    @Transactional(readOnly = true)
    public List<ChatMessage> historyEntitiesForInternalUse(String username, String sessionId) {
        AppUser user = requireUser(username);
        requireOwnedSession(user, sessionId);
        return messageRepo.findBySession_IdOrderByCreatedAtAsc(sessionId);
    }

    // =========================================================================
    // SESIONES
    // =========================================================================

    public String activeSessionId(String username) {
        AppUser user = requireUser(username);
        return sessionRepo.findFirstByUser_IdAndExternalUserIdIsNullOrderByLastActivityAtDesc(user.getId())
                .map(ChatSession::getId)
                .orElseGet(() -> createSession(user).getId());
    }

    @Transactional
    public String newSession(String username) {
        AppUser user = requireUser(username);
        return createSession(user).getId();
    }

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
        sessionRepo.delete(s);
    }

    @Transactional
    public int deleteAllSessions(String username) {
        AppUser user = requireUser(username);
        List<ChatSession> sessions = sessionRepo.findByUser_Id(user.getId());
        if (sessions.isEmpty()) {
            return 0;
        }
        sessionRepo.deleteAll(sessions);
        return sessions.size();
    }

    public SessionDetailsDto sessionDetails(String username, String sessionId) {
        AppUser user = requireUser(username);
        return sessionRepo.findDetails(user.getId(), sessionId)
                .orElseThrow(() -> new AccessDeniedException(
                        "Sesion no encontrada o no pertenece al usuario"
                ));
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private AppUser requireUser(String username) {
        return userRepo.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Usuario autenticado no existe en BD: " + username));
    }

    private ChatSession resolveSession(AppUser user, String maybeSessionId) {
        return resolveSession(user, maybeSessionId, null);
    }

    private ChatSession resolveSession(AppUser user, String maybeSessionId, String externalUserId) {
        if (hasText(maybeSessionId)) {
            ChatSession s = sessionRepo.findById(maybeSessionId)
                    .orElseThrow(() -> new NoSuchElementException("Sesion no encontrada: " + maybeSessionId));

            if (!Objects.equals(s.getUser().getId(), user.getId())) {
                throw new AccessDeniedException("No puedes acceder a sesiones de otro usuario");
            }

            if (hasText(externalUserId)) {
                if (!externalUserId.equals(s.getExternalUserId())) {
                    throw new AccessDeniedException("La sesion no corresponde al usuario externo solicitado.");
                }
            } else if (hasText(s.getExternalUserId())) {
                throw new AccessDeniedException("La sesion pertenece a un usuario externo aislado y requiere modo especial.");
            }

            return s;
        }

        if (hasText(externalUserId)) {
            return sessionRepo.findFirstByUser_IdAndExternalUserIdOrderByLastActivityAtDesc(user.getId(), externalUserId)
                    .orElseGet(() -> createSession(user, externalUserId));
        }

        return sessionRepo.findFirstByUser_IdAndExternalUserIdIsNullOrderByLastActivityAtDesc(user.getId())
                .orElseGet(() -> createSession(user, null));
    }

    private ChatSession requireOwnedSession(AppUser user, String sessionId) {
        ChatSession s = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Sesion no encontrada: " + sessionId));

        if (!Objects.equals(s.getUser().getId(), user.getId())) {
            throw new AccessDeniedException("No puedes acceder a sesiones de otro usuario");
        }
        if (hasText(s.getExternalUserId())) {
            throw new AccessDeniedException("La sesion pertenece a un usuario externo aislado y no esta disponible en modo generico.");
        }
        return s;
    }

    private ChatSession createSession(AppUser user) {
        return createSession(user, null);
    }

    private ChatSession createSession(AppUser user, String externalUserId) {
        SystemPrompt active = promptService.activePromptOrThrow();

        ChatSession s = new ChatSession();
        s.setId(UUID.randomUUID().toString());
        s.setUser(user);
        s.setSystemPrompt(active);
        s.setTitle(DEFAULT_TITLE);
        s.setLastActivityAt(Instant.now());
        s.setExternalUserId(externalUserId);

        return sessionRepo.save(s);
    }

    private void touchSession(ChatSession s) {
        s.setLastActivityAt(Instant.now());
        sessionRepo.save(s);
    }

    private void autoTitleIfDefault(ChatSession s, String userText) {
        if (s.getTitle() != null && !s.getTitle().equalsIgnoreCase(DEFAULT_TITLE)) {
            return;
        }

        String t = normalizeAutoTitle(userText);
        if (t.isEmpty()) {
            return;
        }
        s.setTitle(t);
        sessionRepo.save(s);
    }

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

    private String normalizeAutoTitle(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return "";
        }
        t = t.replaceAll("\\s+", " ");
        if (t.length() > AUTO_TITLE_MAX_LENGTH) {
            t = t.substring(0, AUTO_TITLE_MAX_LENGTH) + "...";
        }
        return t;
    }

    private String normalizeExternalUserId(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        String clean = raw.trim();
        if (clean.length() > 160) {
            clean = clean.substring(0, 160);
        }
        return clean;
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private boolean hasRagContext(List<RagService.ScoredChunk> scored) {
        return scored != null && !scored.isEmpty();
    }

    private String buildRetrievalQuery(String sessionId, String userText, List<PreparedMedia> media) {
        String current = collapseSpaces(userText);
        if (current.isBlank()) {
            return "";
        }

        StringBuilder sb = new StringBuilder(current.length() + 640);
        sb.append(current);

        int turns = Math.max(1, retrievalUserTurns);
        if (turns > 1) {
            List<ChatMessage> userTurnsDesc = messageRepo.findRecentBySessionAndRole(
                    sessionId,
                    ChatMessage.Role.USER,
                    PageRequest.of(0, turns)
            );

            if (userTurnsDesc.size() > 1) {
                sb.append("\n\nContexto reciente del usuario:\n");
                for (int i = userTurnsDesc.size() - 1; i >= 1; i--) {
                    String previous = collapseSpaces(userTurnsDesc.get(i).getContent());
                    if (previous.isBlank()) {
                        continue;
                    }
                    if (previous.length() > 220) {
                        previous = previous.substring(0, 220);
                    }
                    sb.append("- ").append(previous).append('\n');
                }
            }
        }

        if (media != null && !media.isEmpty()) {
            boolean hasDoc = media.stream().anyMatch(m -> hasText(m.documentText()));
            if (hasDoc) {
                sb.append("\nContenido adjunto:\n");
                for (PreparedMedia m : media) {
                    if (!hasText(m.documentText())) {
                        continue;
                    }
                    String snippet = collapseSpaces(m.documentText());
                    if (snippet.length() > MAX_RETRIEVAL_MEDIA_SNIPPET) {
                        snippet = snippet.substring(0, MAX_RETRIEVAL_MEDIA_SNIPPET);
                    }
                    sb.append("- ").append(snippet).append('\n');
                }
            }
        }

        return sb.toString();
    }

    private String collapseSpaces(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private void appendRecentHistory(List<OllamaClient.Message> target, String sessionId, Long excludeMessageId) {
        int historyLimit = Math.max(0, maxHistory);
        if (historyLimit == 0) {
            return;
        }

        List<ChatMessage> recentDesc = messageRepo.findRecentForContext(
                sessionId,
                excludeMessageId,
                PageRequest.of(0, historyLimit)
        );

        for (int i = recentDesc.size() - 1; i >= 0; i--) {
            ChatMessage m = recentDesc.get(i);
            target.add(new OllamaClient.Message(
                    m.getRole() == ChatMessage.Role.USER ? "user" : "assistant",
                    m.getContent()
            ));
        }
    }

    private void persistSources(ChatMessage assistantMsg, List<RagService.ScoredChunk> scored) {
        if (scored == null || scored.isEmpty()) {
            return;
        }

        List<ChatMessageSource> links = new ArrayList<>(scored.size());
        for (var sc : scored) {
            ChatMessageSource link = new ChatMessageSource();
            link.setMessage(assistantMsg);
            link.setChunk(sc.chunk());
            link.setScore(sc.score());
            links.add(link);
        }
        sourceRepo.saveAll(links);
    }

    private String buildRagBlock(String userText,
                                 List<RagService.ScoredChunk> scored,
                                 String visualBridge,
                                 List<PreparedMedia> media) {
        StringBuilder sb = new StringBuilder();
        sb.append("Fuentes RAG (ordenadas por relevancia):\n");

        if (scored != null && !scored.isEmpty()) {
            for (int i = 0; i < scored.size(); i++) {
                var scoredChunk = scored.get(i);
                var c = scoredChunk.chunk();
                sb.append("\n[S").append(i + 1).append("] ")
                        .append("doc=\"").append(c.getDocument().getTitle()).append("\" ")
                        .append("(chunk ").append(c.getChunkIndex()).append(") ")
                        .append("score=").append(String.format(Locale.US, "%.3f", scoredChunk.score()))
                        .append("\n")
                        .append(c.getText())
                        .append("\n");
            }
        } else {
            sb.append("(sin contexto relevante)\n");
        }

        if (hasText(visualBridge)) {
            sb.append("\n---\n");
            sb.append("Contexto visual/documental intermedio (Qwen-VL):\n");
            sb.append(visualBridge).append("\n");
        }

        if (media != null && !media.isEmpty()) {
            sb.append("\nAdjuntos recibidos:\n");
            for (PreparedMedia m : media) {
                sb.append("- ")
                        .append(m.name())
                        .append(" [")
                        .append(m.mimeType())
                        .append("]");
                if (hasText(m.documentText())) {
                    sb.append(" (con texto extraido)");
                }
                if (hasText(m.imageBase64())) {
                    sb.append(" (imagen)");
                }
                sb.append('\n');
            }
        }

        sb.append("\n---\n");
        sb.append("Instrucciones:\n");
        sb.append("- Usa las fuentes solo si ayudan y cita como [S#].\n");
        sb.append("- Si ninguna fuente aplica, indica que no hay datos suficientes.\n");
        sb.append("- Responde en castellano.\n");
        sb.append("- Formatea en Markdown con titulos y subtitulos claros.\n");
        sb.append("- Usa emojis de forma moderada para separar secciones.\n");
        sb.append("- Si incluyes codigo, usa bloques con triple backticks y lenguaje (ej: ```java).\n\n");
        sb.append("Pregunta del usuario: ").append(userText);

        return sb.toString();
    }

    private String applyResponseGuard(String userText,
                                      String assistantText,
                                      List<RagService.ScoredChunk> scored) {
        if (!responseGuardEnabled || !hasText(assistantText)) {
            return assistantText;
        }

        String original = assistantText.trim();
        boolean triggeredByLength = original.length() >= Math.max(80, responseGuardMinAnswerChars);
        boolean triggeredByFiller = ChatPromptSignals.hasLikelyFiller(original);
        if (!triggeredByLength && !triggeredByFiller) {
            return original;
        }

        String guardModel = modelSelector.resolveResponseGuardModel();
        if (!hasText(guardModel)) {
            return original;
        }

        String question = truncateForGuard(collapseSpaces(userText), MAX_GUARD_QUESTION_CHARS);
        String sourceHints = buildSourceHints(scored);
        String currentAnswer = truncateForGuard(original, MAX_GUARD_ANSWER_CHARS);

        StringBuilder guardPrompt = new StringBuilder();
        guardPrompt.append("Pregunta original:\n").append(question).append("\n\n");
        if (hasText(sourceHints)) {
            guardPrompt.append("Fuentes citables disponibles:\n").append(sourceHints).append("\n\n");
        }
        guardPrompt.append("Respuesta actual (a depurar):\n").append(currentAnswer);

        String guardSystemPrompt = responseGuardStrictMode
                ? """
                        Eres un editor ultra-estricto de respuestas RAG.
                        Objetivo: entregar solo informacion util y accionable, sin relleno.
                        Reglas obligatorias:
                        - No inventes ni agregues informacion nueva.
                        - Conserva hechos, cifras, pasos, advertencias y limites.
                        - Elimina introducciones, despedidas, redundancias y texto decorativo.
                        - Si hay citas [S#], mantenlas.
                        - Si hay codigo, conserva los bloques ``` con su lenguaje.
                        - Manten Markdown limpio y directo, con titulos cortos.
                        - Devuelve solo la version final depurada.
                        """
                : """
                        Eres un editor estricto de respuestas RAG.
                        Objetivo: eliminar relleno, repeticiones y texto irrelevante.
                        Reglas obligatorias:
                        - No inventes ni agregues informacion nueva.
                        - Conserva hechos, cifras, pasos y limitaciones.
                        - Si hay citas [S#], mantenlas.
                        - Si hay codigo, conserva los bloques ``` con su lenguaje.
                        - Manten formato Markdown limpio con titulos/subtitulos y emojis moderados.
                        - Devuelve solo la version final depurada.
                        """;

        List<OllamaClient.Message> guardMessages = List.of(
                new OllamaClient.Message(
                        "system",
                        guardSystemPrompt
                ),
                new OllamaClient.Message("user", guardPrompt.toString())
        );

        try {
            String refined = ollama.chat(guardMessages, guardModel);
            if (!hasText(refined)) {
                return original;
            }

            String clean = refined.trim();
            int minGuardOutputChars = triggeredByLength ? 80 : 40;
            if (clean.length() < minGuardOutputChars) {
                return original;
            }
            if (clean.length() > original.length() + 220) {
                return original;
            }
            if (responseGuardStrictMode && clean.length() >= original.length()) {
                return original;
            }
            if (original.contains("[S") && !clean.contains("[S")) {
                return original;
            }
            if (original.contains("```") && !clean.contains("```")) {
                return original;
            }

            double ratio = (double) clean.length() / (double) Math.max(1, original.length());
            double minRatio;
            if (triggeredByFiller) {
                minRatio = responseGuardStrictMode ? 0.16 : 0.25;
            } else {
                minRatio = responseGuardStrictMode ? 0.22 : 0.35;
            }
            if (ratio < minRatio) {
                return original;
            }
            return clean;
        } catch (Exception ex) {
            log.warn("No se pudo depurar respuesta con mini-modelo: {}", ex.getMessage());
            return original;
        }
    }

    private String buildSourceHints(List<RagService.ScoredChunk> scored) {
        if (scored == null || scored.isEmpty()) {
            return "";
        }

        int max = Math.min(MAX_GUARD_SOURCE_HINTS, scored.size());
        StringBuilder sb = new StringBuilder(max * 180);
        for (int i = 0; i < max; i++) {
            RagService.ScoredChunk entry = scored.get(i);
            String title = entry.chunk().getDocument().getTitle();
            String snippet = collapseSpaces(entry.chunk().getText());
            if (snippet.length() > 120) {
                snippet = snippet.substring(0, 120) + "...";
            }
            sb.append("- [S").append(i + 1).append("] ")
                    .append(title)
                    .append(": ")
                    .append(snippet)
                    .append('\n');
        }
        return sb.toString().trim();
    }

    private String truncateForGuard(String value, int maxChars) {
        if (!hasText(value)) {
            return "";
        }
        String clean = value.trim();
        if (clean.length() <= maxChars) {
            return clean;
        }
        return clean.substring(0, maxChars);
    }

    private String buildVisualBridgeContext(String userText,
                                            List<PreparedMedia> media,
                                            String requestedModel) {
        if (media == null || media.isEmpty()) {
            return "";
        }

        List<String> images = media.stream()
                .map(PreparedMedia::imageBase64)
                .filter(this::hasText)
                .toList();
        if (images.isEmpty()) {
            // Politica: el modelo visual solo se ejecuta para entradas con imagen.
            return "";
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("Analiza el material visual/documental y extrae hechos verificables.\n");
        prompt.append("Responde SOLO con:\n");
        prompt.append("1) Observaciones clave\n");
        prompt.append("2) Datos concretos\n");
        prompt.append("3) Incertidumbres o limites\n\n");

        for (PreparedMedia m : media) {
            if (!hasText(m.documentText())) {
                continue;
            }
            String text = m.documentText();
            if (text.length() > 2200) {
                text = text.substring(0, 2200);
            }
            prompt.append("Documento '").append(m.name()).append("':\n");
            prompt.append(text).append("\n\n");
        }

        prompt.append("Pregunta del usuario: ").append(userText);

        try {
            String visualModel = modelSelector.resolveVisualModel(requestedModel);
            String bridge = ollama.chat(
                    List.of(new OllamaClient.Message("user", prompt.toString(), images)),
                    visualModel
            );

            if (!hasText(bridge)) {
                return "";
            }
            String clean = bridge.trim();
            if (clean.length() > MAX_VISUAL_CONTEXT_CHARS) {
                clean = clean.substring(0, MAX_VISUAL_CONTEXT_CHARS);
            }
            return clean;
        } catch (Exception ex) {
            log.warn("No se pudo completar analisis visual intermedio: {}", ex.getMessage());
            return "";
        }
    }

    private List<PreparedMedia> prepareMedia(List<ChatMediaInput> media) {
        if (media == null || media.isEmpty()) {
            return List.of();
        }

        List<PreparedMedia> prepared = new ArrayList<>();
        for (ChatMediaInput raw : media) {
            if (raw == null) {
                continue;
            }
            if (prepared.size() >= MAX_MEDIA_ITEMS) {
                break;
            }

            String name = sanitizeName(raw.getName());
            String mime = sanitizeMimeType(raw.getMimeType());
            String base64 = sanitizeBase64(raw.getBase64());
            String directText = sanitizeText(raw.getText(), MAX_MEDIA_TEXT_CHARS);

            String imageBase64 = "";
            if (isImageMime(mime) && hasText(base64)) {
                imageBase64 = base64;
            }

            String docText = "";
            if (hasText(directText)) {
                docText = directText;
            } else if (hasText(base64) && isPdfMime(mime)) {
                docText = sanitizeText(extractPdfText(base64), MAX_MEDIA_TEXT_CHARS);
            } else if (hasText(base64) && isTextMime(mime)) {
                docText = sanitizeText(decodeBase64AsText(base64), MAX_MEDIA_TEXT_CHARS);
            }

            if (!hasText(imageBase64) && !hasText(docText)) {
                continue;
            }

            prepared.add(new PreparedMedia(name, mime, imageBase64, docText));
        }

        return Collections.unmodifiableList(prepared);
    }

    private String sanitizeName(String name) {
        String clean = hasText(name) ? name.trim() : "archivo";
        if (clean.length() > 120) {
            clean = clean.substring(0, 120);
        }
        return clean;
    }

    private String sanitizeMimeType(String mime) {
        String clean = hasText(mime) ? mime.trim().toLowerCase(Locale.ROOT) : "application/octet-stream";
        if (clean.length() > 120) {
            clean = clean.substring(0, 120);
        }
        return clean;
    }

    private String sanitizeText(String text, int maxChars) {
        if (!hasText(text)) {
            return "";
        }
        String clean = text.replaceAll("\\u0000", "").trim();
        if (clean.length() > maxChars) {
            clean = clean.substring(0, maxChars);
        }
        return clean;
    }

    private String sanitizeBase64(String value) {
        if (!hasText(value)) {
            return "";
        }
        String clean = value.trim();
        int comma = clean.indexOf(',');
        if (comma >= 0) {
            clean = clean.substring(comma + 1);
        }
        clean = clean.replaceAll("\\s+", "");
        if (clean.length() > 12_000_000) {
            clean = clean.substring(0, 12_000_000);
        }
        return clean;
    }

    private boolean isImageMime(String mime) {
        return hasText(mime) && mime.startsWith("image/");
    }

    private boolean isPdfMime(String mime) {
        return "application/pdf".equalsIgnoreCase(mime);
    }

    private boolean isTextMime(String mime) {
        if (!hasText(mime)) {
            return false;
        }
        return mime.startsWith("text/")
                || mime.contains("json")
                || mime.contains("xml")
                || mime.contains("csv")
                || mime.contains("javascript");
    }

    private String extractPdfText(String base64) {
        byte[] bytes = decodeBase64(base64);
        if (bytes.length == 0) {
            return "";
        }

        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            return sanitizeText(text, MAX_MEDIA_TEXT_CHARS);
        } catch (Exception ex) {
            log.warn("No se pudo extraer texto PDF: {}", ex.getMessage());
            return "";
        }
    }

    private String decodeBase64AsText(String base64) {
        byte[] bytes = decodeBase64(base64);
        if (bytes.length == 0) {
            return "";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private byte[] decodeBase64(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (Exception ex) {
            return new byte[0];
        }
    }

    private record PreparedMedia(String name, String mimeType, String imageBase64, String documentText) {
    }
}
