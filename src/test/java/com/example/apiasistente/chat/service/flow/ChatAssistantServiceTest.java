package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.entity.ChatMessage;
import com.example.apiasistente.chat.entity.ChatSession;
import com.example.apiasistente.prompt.entity.SystemPrompt;
import com.example.apiasistente.chat.service.ChatPromptSignals;
import com.example.apiasistente.chat.service.ChatTurnPlanner;
import com.example.apiasistente.shared.ai.OllamaClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/**
 * Pruebas para Chat Assistant Service.
 */
class ChatAssistantServiceTest {

    @Mock
    private OllamaClient ollama;

    @Mock
    private ChatPromptBuilder promptBuilder;

    @Mock
    private ChatMediaService mediaService;

    @Mock
    private ChatGroundingService groundingService;

    @Mock
    private ChatHistoryService historyService;

    private ChatAssistantService service;

    @BeforeEach
    void setUp() {
        service = new ChatAssistantService(
                ollama,
                promptBuilder,
                mediaService,
                groundingService,
                historyService
        );
    }

    @Test
    void returnsWeakFallbackWithoutCallingModel() {
        ChatTurnContext context = context("Explica el endpoint");
        ChatRagContext ragContext = new ChatRagContext(
                List.of(),
                List.of(),
                new ChatGroundingService.GroundingDecision(false, 0.12, 0, 0.10),
                false,
                ChatGroundingService.RagRoute.WEAK,
                true,
                true,
                true,
                "fallback"
        );

        when(groundingService.weakFallback("Explica el endpoint")).thenReturn("weak fallback");

        ChatAssistantOutcome outcome = service.answer(context, ragContext);

        assertEquals("weak fallback", outcome.assistantText());
        assertFalse(outcome.answerAssessment().safe());
        verifyNoInteractions(ollama, promptBuilder, mediaService, historyService);
    }

    @Test
    void returnsGroundingFallbackBeforeBuildingMessages() {
        ChatTurnContext context = context("Que dice la documentacion?");
        ChatRagContext ragContext = new ChatRagContext(
                List.of(),
                List.of(),
                new ChatGroundingService.GroundingDecision(false, 0.22, 1, 0.21),
                false,
                ChatGroundingService.RagRoute.STRONG,
                true,
                false,
                true,
                "sin contexto"
        );

        ChatAssistantOutcome outcome = service.answer(context, ragContext);

        assertEquals("sin contexto", outcome.assistantText());
        assertFalse(outcome.answerAssessment().safe());
        verifyNoInteractions(ollama, promptBuilder, mediaService, historyService);
    }

    private ChatTurnContext context(String userText) {
        SystemPrompt prompt = new SystemPrompt();
        prompt.setContent("sistema");

        ChatSession session = new ChatSession();
        session.setId("sid-1");
        session.setSystemPrompt(prompt);

        ChatMessage userMessage = new ChatMessage();
        userMessage.setSession(session);
        userMessage.setRole(ChatMessage.Role.USER);
        userMessage.setContent(userText);

        ChatTurnPlanner.TurnPlan turnPlan = new ChatTurnPlanner.TurnPlan(
                ChatPromptSignals.IntentRoute.FACTUAL_TECH,
                true,
                ChatTurnPlanner.ReasoningLevel.MEDIUM,
                false,
                false,
                0.8
        );

        return new ChatTurnContext(
                "user",
                userText,
                "auto",
                null,
                session,
                userMessage,
                List.of(),
                turnPlan,
                ChatPromptSignals.IntentRoute.FACTUAL_TECH,
                true,
                false,
                false,
                false,
                false,
                false
        );
    }
}


