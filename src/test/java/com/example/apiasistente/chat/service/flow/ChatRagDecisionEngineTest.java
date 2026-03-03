package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.service.ChatModelSelector;
import com.example.apiasistente.chat.service.ChatPromptSignals;
import com.example.apiasistente.chat.service.ChatTurnPlanner;
import com.example.apiasistente.shared.ai.OllamaClient;
import com.example.apiasistente.shared.config.OllamaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatRagDecisionEngineTest {

    @Mock
    private OllamaClient ollama;

    private ChatRagDecisionEngine service;

    @BeforeEach
    void setUp() {
        OllamaProperties properties = new OllamaProperties();
        properties.setChatModel("chat-model");
        properties.setFastChatModel("fast-model");
        ChatModelSelector modelSelector = new ChatModelSelector(properties);
        service = new ChatRagDecisionEngine(ollama, modelSelector);
        ReflectionTestUtils.setField(service, "decisionEnabled", true);
        ReflectionTestUtils.setField(service, "llmAssessmentEnabled", true);
        ReflectionTestUtils.setField(service, "verifyNoRagAnswers", true);
        ReflectionTestUtils.setField(service, "heuristicConfidenceThreshold", 0.72);
        ReflectionTestUtils.setField(service, "selfConfidenceThreshold", 0.70);
        ReflectionTestUtils.setField(service, "technicalConfidenceThreshold", 0.80);
        ReflectionTestUtils.setField(service, "verifyAnswerConfidenceThreshold", 0.74);
        ReflectionTestUtils.setField(service, "minAnswerChars", 50);
        ReflectionTestUtils.setField(service, "cacheTtlMs", 60_000L);
        ReflectionTestUtils.setField(service, "maxCacheEntries", 64);
    }

    @Test
    void cachesLlmAssessmentForRepeatedTechnicalQuery() {
        when(ollama.chat(anyList(), eq("fast-model")))
                .thenReturn("""
                        {"type":"technical","needs_external_context":true,"confidence":0.58,"reason":"necesita base interna"}
                        """);

        ChatTurnPlanner.TurnPlan turnPlan = new ChatTurnPlanner.TurnPlan(
                ChatPromptSignals.IntentRoute.FACTUAL_TECH,
                true,
                ChatTurnPlanner.ReasoningLevel.MEDIUM,
                false,
                false,
                0.77,
                ChatPromptSignals.RagDecision.preferred("Consulta tecnica", List.of("consulta-tecnica"))
        );

        ChatRagDecisionEngine.DecisionAssessment first = service.assessQuery(
                "Explica la latencia del endpoint interno",
                turnPlan,
                false
        );
        ChatRagDecisionEngine.DecisionAssessment second = service.assessQuery(
                "Explica la latencia del endpoint interno",
                turnPlan,
                false
        );

        assertTrue(first.needsRag());
        assertTrue(first.usedLlm());
        assertFalse(first.cacheHit());
        assertTrue(second.cacheHit());
        verify(ollama, times(1)).chat(anyList(), eq("fast-model"));
    }

    @Test
    void verifiesDirectAnswerAndRequestsRagRetryWhenConfidenceIsLow() {
        when(ollama.chat(anyList(), eq("fast-model")))
                .thenReturn("""
                        {"retry_with_rag":false,"confidence":0.32,"reason":"respuesta dudosa"}
                        """);

        ChatTurnPlanner.TurnPlan turnPlan = new ChatTurnPlanner.TurnPlan(
                ChatPromptSignals.IntentRoute.FACTUAL_TECH,
                false,
                ChatTurnPlanner.ReasoningLevel.MEDIUM,
                false,
                false,
                0.66,
                ChatPromptSignals.RagDecision.off("Consulta general")
        );

        ChatRagDecisionEngine.AnswerVerification verification = service.verifyDirectAnswer(
                "Explica el fallo del endpoint",
                "Podria ser un timeout o alguna configuracion antigua sin ver tus datos.",
                turnPlan
        );

        assertTrue(verification.reviewed());
        assertTrue(verification.retryWithRag());
    }
}
