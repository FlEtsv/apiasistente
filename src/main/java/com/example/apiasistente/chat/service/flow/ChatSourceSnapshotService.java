package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.entity.ChatMessageSource;
import com.example.apiasistente.chat.repository.ChatMessageRepository;
import com.example.apiasistente.chat.repository.ChatMessageSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Persiste snapshots de fuentes RAG fuera de la transaccion principal del turno.
 * Asi un fallo en trazabilidad no invalida la respuesta ya generada para el usuario.
 */
@Service
public class ChatSourceSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(ChatSourceSnapshotService.class);

    private final ChatMessageRepository messageRepo;
    private final ChatMessageSourceRepository sourceRepo;

    public ChatSourceSnapshotService(ChatMessageRepository messageRepo,
                                     ChatMessageSourceRepository sourceRepo) {
        this.messageRepo = messageRepo;
        this.sourceRepo = sourceRepo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistAfterCommit(Long assistantMessageId,
                                   String sessionId,
                                   List<SourceSnapshot> snapshots) {
        if (assistantMessageId == null || snapshots == null || snapshots.isEmpty()) {
            return;
        }

        var assistantRef = messageRepo.getReferenceById(assistantMessageId);
        List<ChatMessageSource> links = new ArrayList<>(snapshots.size());
        for (SourceSnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.sourceDocumentId() == null) {
                continue;
            }
            ChatMessageSource link = new ChatMessageSource();
            link.setMessage(assistantRef);
            link.setSourceChunkId(snapshot.sourceChunkId());
            link.setSourceDocumentId(snapshot.sourceDocumentId());
            link.setSourceDocumentTitle(snapshot.sourceDocumentTitle());
            link.setSourceSnippet(snapshot.sourceSnippet());
            link.setScore(snapshot.score());
            links.add(link);
        }
        if (links.isEmpty()) {
            return;
        }

        sourceRepo.saveAll(links);
        log.info(
                "chat_sources_persisted sessionId={} messageId={} sourceCount={}",
                sessionId == null ? "" : sessionId,
                assistantMessageId,
                links.size()
        );
    }

    public List<SourceSnapshot> toSnapshots(List<com.example.apiasistente.rag.service.RagService.ScoredChunk> scored) {
        if (scored == null || scored.isEmpty()) {
            return List.of();
        }

        List<SourceSnapshot> snapshots = new ArrayList<>(scored.size());
        for (com.example.apiasistente.rag.service.RagService.ScoredChunk scoredChunk : scored) {
            if (scoredChunk == null || scoredChunk.chunk() == null || scoredChunk.chunk().getDocument() == null) {
                continue;
            }
            snapshots.add(new SourceSnapshot(
                    scoredChunk.chunk().getId(),
                    scoredChunk.chunk().getDocument().getId(),
                    scoredChunk.chunk().getDocument().getTitle(),
                    scoredChunk.effectiveText(),
                    scoredChunk.score()
            ));
        }
        return snapshots.isEmpty() ? List.of() : List.copyOf(snapshots);
    }

    public record SourceSnapshot(Long sourceChunkId,
                                 Long sourceDocumentId,
                                 String sourceDocumentTitle,
                                 String sourceSnippet,
                                 double score) {
    }
}
