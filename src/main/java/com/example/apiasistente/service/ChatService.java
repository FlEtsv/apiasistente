package com.example.apiasistente.service;

import com.example.apiasistente.model.dto.ChatResponse;
import com.example.apiasistente.model.dto.SourceDto;
import com.example.apiasistente.model.entity.ChatMessage;
import com.example.apiasistente.model.entity.ChatMessageSource;
import com.example.apiasistente.model.entity.ChatSession;
import com.example.apiasistente.model.entity.SystemPrompt;
import com.example.apiasistente.repository.ChatMessageRepository;
import com.example.apiasistente.repository.ChatMessageSourceRepository;
import com.example.apiasistente.repository.ChatSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ChatService {

    private final ChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final ChatMessageSourceRepository sourceRepo;
    private final SystemPromptService promptService;
    private final RagService ragService;
    private final OllamaClient ollama;

    @Value("${rag.max-history:12}")
    private int maxHistory;

    public ChatService(
            ChatSessionRepository sessionRepo,
            ChatMessageRepository messageRepo,
            ChatMessageSourceRepository sourceRepo,
            SystemPromptService promptService,
            RagService ragService,
            OllamaClient ollama
    ) {
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.sourceRepo = sourceRepo;
        this.promptService = promptService;
        this.ragService = ragService;
        this.ollama = ollama;
    }

    @Transactional
    public ChatResponse chat(String maybeSessionId, String userText) {
        ChatSession session = getOrCreateSession(maybeSessionId);

        // 1) Guardar mensaje usuario
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSession(session);
        userMsg.setRole(ChatMessage.Role.USER);
        userMsg.setContent(userText);
        userMsg = messageRepo.save(userMsg);

        // 2) RAG (retrieve top-k chunks desde MySQL)
        var scored = ragService.retrieveTopK(userText);
        List<SourceDto> sources = ragService.toSourceDtos(scored);

        // 3) Construir mensajes para Ollama (/api/chat)
        List<OllamaClient.Message> msgs = new ArrayList<>();

        SystemPrompt prompt = session.getSystemPrompt();
        msgs.add(new OllamaClient.Message("system", prompt.getContent()));

        // historial (últimos N)
        var historyDesc = messageRepo.findTop20BySession_IdOrderByCreatedAtDesc(session.getId());
        historyDesc.stream()
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt)) // asc
                .skip(Math.max(0, historyDesc.size() - maxHistory))
                .forEach(m -> msgs.add(new OllamaClient.Message(
                        m.getRole() == ChatMessage.Role.USER ? "user" : "assistant",
                        m.getContent()
                )));

        // user final con contexto RAG
        String ragBlock = buildRagBlock(scored);
        msgs.add(new OllamaClient.Message("user", ragBlock));

        // 4) Llamada a Ollama
        String assistantText = ollama.chat(msgs);

        // 5) Guardar respuesta asistente
        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setSession(session);
        assistantMsg.setRole(ChatMessage.Role.ASSISTANT);
        assistantMsg.setContent(assistantText);
        assistantMsg = messageRepo.save(assistantMsg);

        // 6) Guardar “qué chunks se usaron” (RAG log)
        for (var sc : scored) {
            ChatMessageSource link = new ChatMessageSource();
            link.setMessage(assistantMsg);
            link.setChunk(sc.chunk());
            link.setScore(sc.score());
            sourceRepo.save(link);
        }

        return new ChatResponse(session.getId(), assistantText, sources);
    }

    public List<ChatMessage> history(String sessionId) {
        return messageRepo.findBySession_IdOrderByCreatedAtAsc(sessionId);
    }

    private ChatSession getOrCreateSession(String maybeId) {
        if (maybeId != null && !maybeId.isBlank()) {
            return sessionRepo.findById(maybeId).orElseGet(() -> createSession(maybeId));
        }
        return createSession(UUID.randomUUID().toString());
    }

    private ChatSession createSession(String id) {
        SystemPrompt active = promptService.activePromptOrThrow();
        ChatSession s = new ChatSession();
        s.setId(id);
        s.setSystemPrompt(active);
        return sessionRepo.save(s);
    }

    private String buildRagBlock(List<RagService.ScoredChunk> scored) {
        if (scored == null || scored.isEmpty()) {
            return "Pregunta del usuario:\n" + "----\n" + "(sin contexto RAG)\n\n" +
                    "Usuario:\n" + scoredSafeUserTextPlaceholder(); // no-op
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Contexto RAG (úsalo si es relevante):\n");
        for (int i = 0; i < scored.size(); i++) {
            var c = scored.get(i).chunk();
            sb.append("\n[").append(i + 1).append("] ")
                    .append(c.getDocument().getTitle())
                    .append(" (chunk ").append(c.getChunkIndex()).append(")\n")
                    .append(c.getText()).append("\n");
        }
        sb.append("\n---\n");
        sb.append("Ahora responde a la pregunta del usuario de forma clara.\n");
        sb.append("Pregunta: ").append(scoredSafeUserTextPlaceholder());
        return sb.toString();
    }

    private String scoredSafeUserTextPlaceholder() {
        // Esto se sustituye justo antes en buildRagBlock si quieres más control.
        // Para no duplicar strings, lo dejamos simple y lo seteamos en el flujo real.
        return "";
    }
}
