package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.service.ChatPromptSignals;
import com.example.apiasistente.chat.service.ChatTurnPlanner;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatRagGateServiceTest {

    @Mock
    private KnowledgeDocumentRepository documentRepository;

    @Mock
    private KnowledgeChunkRepository chunkRepository;

    @Mock
    private ChatRagDecisionEngine decisionEngine;

    private ChatRagGateService service;

    @BeforeEach
    void setUp() {
        service = new ChatRagGateService(documentRepository, chunkRepository, decisionEngine);
        ReflectionTestUtils.setField(service, "gateEnabled", true);
        ReflectionTestUtils.setField(service, "minPreferredQueryChars", 18);
        ReflectionTestUtils.setField(service, "minPreferredQueryTokens", 2);
        ReflectionTestUtils.setField(service, "maxProbeTerms", 3);
    }

    @Test
    void preferredRagUsesMetadataHitsFromNewStructure() {
        when(documentRepository.countByActiveTrue()).thenReturn(3L);
        when(chunkRepository.countActive()).thenReturn(25L);
        when(documentRepository.countActiveMetadataMatchesAll(anyString())).thenReturn(1L);
        when(decisionEngine.assessQuery(anyString(), any(ChatTurnPlanner.TurnPlan.class), eq(false)))
                .thenReturn(new ChatRagDecisionEngine.DecisionAssessment(
                        ChatRagDecisionEngine.QueryType.TECHNICAL,
                        true,
                        true,
                        0.61,
                        0.78,
                        true,
                        false,
                        true,
                        "hybrid-llm",
                        "llm-low-confidence"
                ));

        ChatRagGateService.GateDecision decision = service.evaluate(
                new ChatTurnPlanner.TurnPlan(
                        ChatPromptSignals.IntentRoute.FACTUAL_TECH,
                        true,
                        ChatTurnPlanner.ReasoningLevel.MEDIUM,
                        false,
                        false,
                        0.78,
                        ChatPromptSignals.RagDecision.preferred("Consulta tecnica", List.of("consulta-tecnica"))
                ),
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
        when(documentRepository.countByActiveTrue()).thenReturn(3L);
        when(chunkRepository.countActive()).thenReturn(25L);
        when(decisionEngine.assessQuery(anyString(), any(ChatTurnPlanner.TurnPlan.class), eq(false)))
                .thenReturn(new ChatRagDecisionEngine.DecisionAssessment(
                        ChatRagDecisionEngine.QueryType.TECHNICAL,
                        false,
                        false,
                        0.91,
                        0.88,
                        true,
                        false,
                        false,
                        "hybrid-llm",
                        "no-needs-context"
                ));

        ChatRagGateService.GateDecision decision = service.evaluate(
                new ChatTurnPlanner.TurnPlan(
                        ChatPromptSignals.IntentRoute.FACTUAL_TECH,
                        true,
                        ChatTurnPlanner.ReasoningLevel.MEDIUM,
                        false,
                        false,
                        0.88,
                        ChatPromptSignals.RagDecision.preferred("Consulta tecnica", List.of("consulta-tecnica"))
                ),
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
        when(documentRepository.countByActiveTrue()).thenReturn(0L);
        when(chunkRepository.countActive()).thenReturn(0L);
        when(decisionEngine.assessQuery(anyString(), any(ChatTurnPlanner.TurnPlan.class), eq(false)))
                .thenReturn(new ChatRagDecisionEngine.DecisionAssessment(
                        ChatRagDecisionEngine.QueryType.PERSONAL,
                        true,
                        true,
                        0.95,
                        0.95,
                        false,
                        false,
                        false,
                        "heuristic",
                        "contexto-propio"
                ));

        ChatRagGateService.GateDecision decision = service.evaluate(
                new ChatTurnPlanner.TurnPlan(
                        ChatPromptSignals.IntentRoute.FACTUAL_TECH,
                        true,
                        ChatTurnPlanner.ReasoningLevel.MEDIUM,
                        false,
                        false,
                        0.95,
                        ChatPromptSignals.RagDecision.required("Contexto propio", List.of("contexto-propio"))
                ),
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
