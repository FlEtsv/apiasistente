package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.service.ChatPromptSignals;
import com.example.apiasistente.rag.repository.KnowledgeChunkRepository;
import com.example.apiasistente.rag.repository.KnowledgeDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatRagGateServiceTest {

    @Mock
    private KnowledgeDocumentRepository documentRepository;

    @Mock
    private KnowledgeChunkRepository chunkRepository;

    private ChatRagGateService service;

    @BeforeEach
    void setUp() {
        service = new ChatRagGateService(documentRepository, chunkRepository);
        ReflectionTestUtils.setField(service, "gateEnabled", true);
        ReflectionTestUtils.setField(service, "minPreferredQueryChars", 18);
        ReflectionTestUtils.setField(service, "minPreferredQueryTokens", 2);
        ReflectionTestUtils.setField(service, "maxProbeTerms", 3);
    }

    @Test
    void preferredRagUsesMetadataHitsFromNewStructure() {
        when(documentRepository.countByOwnerInAndActiveTrue(anyCollection())).thenReturn(3L);
        when(chunkRepository.countActiveByOwners(anyList())).thenReturn(25L);
        when(documentRepository.countActiveMetadataMatches(anyCollection(), anyString())).thenReturn(1L);
        when(chunkRepository.countActiveTagMatches(anyList(), anyString())).thenReturn(0L);

        ChatRagGateService.GateDecision decision = service.evaluate(
                ChatPromptSignals.RagDecision.preferred("Consulta tecnica", List.of("consulta-tecnica")),
                "Quiero optimizar la latencia del endpoint monitor con Prometheus",
                "user",
                null,
                false
        );

        assertTrue(decision.attemptRag());
        assertFalse(decision.matchedTerms().isEmpty());
    }

    @Test
    void preferredRagSkipsWhenMetadataHasNoSignals() {
        when(documentRepository.countByOwnerInAndActiveTrue(anyCollection())).thenReturn(3L);
        when(chunkRepository.countActiveByOwners(anyList())).thenReturn(25L);
        when(documentRepository.countActiveMetadataMatches(anyCollection(), anyString())).thenReturn(0L);
        when(chunkRepository.countActiveTagMatches(anyList(), anyString())).thenReturn(0L);

        ChatRagGateService.GateDecision decision = service.evaluate(
                ChatPromptSignals.RagDecision.preferred("Consulta tecnica", List.of("consulta-tecnica")),
                "Compara estrategias de cache y rendimiento",
                "user",
                null,
                false
        );

        assertFalse(decision.attemptRag());
        assertFalse(decision.forceNoEvidence());
    }

    @Test
    void requiredRagReturnsNoEvidenceWhenCorpusIsEmpty() {
        when(documentRepository.countByOwnerInAndActiveTrue(anyCollection())).thenReturn(0L);
        when(chunkRepository.countActiveByOwners(anyList())).thenReturn(0L);

        ChatRagGateService.GateDecision decision = service.evaluate(
                ChatPromptSignals.RagDecision.required("Contexto propio", List.of("contexto-propio")),
                "Que paso en nuestro endpoint interno?",
                "user",
                null,
                false
        );

        assertFalse(decision.attemptRag());
        assertTrue(decision.forceNoEvidence());
    }
}
