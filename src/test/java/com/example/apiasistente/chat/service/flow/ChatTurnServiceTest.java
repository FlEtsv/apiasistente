package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.dto.ChatResponse;
import com.example.apiasistente.rag.dto.SourceDto;
import com.example.apiasistente.chat.entity.ChatMessage;
import com.example.apiasistente.chat.entity.ChatSession;
import com.example.apiasistente.prompt.entity.SystemPrompt;
import com.example.apiasistente.chat.service.ChatPromptSignals;
import com.example.apiasistente.chat.service.ChatTurnPlanner;
import com.example.apiasistente.rag.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/**
 * Pruebas para Chat Turn Service.
 */
class ChatTurnServiceTest {

    @Mock
    private ChatTurnContextFactory contextFactory;

    @Mock
    private ChatRagFlowService ragFlowService;

    @Mock
    private ChatAssistantService assistantService;

    @Mock
    private ChatHistoryService historyService;

    @Mock
    private ChatSessionService sessionService;

    @Mock
    private ChatRagDecisionEngine decisionEngine;

    @Mock
    private ChatRagTelemetryService telemetryService;

    private ChatTurnService service;

    @BeforeEach
    void setUp() {
        service = new ChatTurnService(
                contextFactory,
                ragFlowService,
                assistantService,
                historyService,
                sessionService,
                decisionEngine,
                telemetryService
        );
    }

    @Test
    void orchestratesTurnAndPersistsSourcesWhenRagIsUsed() {
        ChatSession session = new ChatSession();
        session.setId("sid-77");
        session.setSystemPrompt(new SystemPrompt());

        ChatMessage userMessage = new ChatMessage();
        ReflectionTestUtils.setField(userMessage, "id", 11L);
        userMessage.setSession(session);
        userMessage.setRole(ChatMessage.Role.USER);
        userMessage.setContent("Que dice el log?");

        ChatTurnContext context = new ChatTurnContext(
                "user",
                "Que dice el log?",
                "auto",
                null,
                session,
                userMessage,
                List.of(),
                new ChatTurnPlanner.TurnPlan(
                        ChatPromptSignals.IntentRoute.FACTUAL_TECH,
                        true,
                        ChatTurnPlanner.ReasoningLevel.MEDIUM,
                        false,
                        false,
                        0.82
                ),
                ChatPromptSignals.IntentRoute.FACTUAL_TECH,
                true,
                false,
                false,
                false,
                false,
                false
        );

        List<SourceDto> sources = List.of(new SourceDto(1L, 2L, "Manual", 0.91, "snippet"));
        List<RagService.ScoredChunk> scored = List.of(new RagService.ScoredChunk(null, 0.91));
        ChatRagContext ragContext = new ChatRagContext(
                scored,
                sources,
                new ChatGroundingService.GroundingDecision(true, 0.91, 2, 0.88),
                true,
                ChatGroundingService.RagRoute.STRONG,
                true,
                false,
                true,
                "fallback"
        );
        ChatAssistantOutcome outcome = new ChatAssistantOutcome(
                "Respuesta final [S1]",
                new ChatGroundingService.GroundingAnswerAssessment(true, 1)
        );

        ChatMessage assistantMessage = new ChatMessage();
        ReflectionTestUtils.setField(assistantMessage, "id", 12L);

        when(contextFactory.create("user", null, "Que dice el log?", "auto", null, List.of())).thenReturn(context);
        when(ragFlowService.resolve(context)).thenReturn(ragContext);
        when(assistantService.answer(context, ragContext)).thenReturn(outcome);
        when(historyService.saveAssistantMessage(session, "Respuesta final [S1]")).thenReturn(assistantMessage);

        ChatResponse response = service.chat("user", null, "Que dice el log?", "auto", null, List.of());

        assertEquals("sid-77", response.getSessionId());
        assertEquals("Respuesta final [S1]", response.getReply());
        assertTrue(response.isRagUsed());
        assertTrue(response.isSafe());
        assertEquals(1, response.getGroundedSources());
        assertEquals("MEDIUM", response.getReasoningLevel());

        verify(historyService).persistSources(assistantMessage, scored);
        verify(sessionService).touchSession(session);
        verify(historyService).saveAssistantMessage(session, "Respuesta final [S1]");
    }

    @Test
    void retriesWithRagWhenPostVerificationFlagsDirectAnswerAsUnsafe() {
        ChatSession session = new ChatSession();
        session.setId("sid-88");
        session.setSystemPrompt(new SystemPrompt());

        ChatMessage userMessage = new ChatMessage();
        ReflectionTestUtils.setField(userMessage, "id", 21L);
        userMessage.setSession(session);
        userMessage.setRole(ChatMessage.Role.USER);
        userMessage.setContent("Explica el error del endpoint");

        ChatTurnPlanner.TurnPlan turnPlan = new ChatTurnPlanner.TurnPlan(
                ChatPromptSignals.IntentRoute.FACTUAL_TECH,
                false,
                ChatTurnPlanner.ReasoningLevel.MEDIUM,
                false,
                false,
                0.63,
                ChatPromptSignals.RagDecision.off("Consulta general")
        );
        ChatTurnContext context = new ChatTurnContext(
                "user",
                "Explica el error del endpoint",
                "auto",
                null,
                session,
                userMessage,
                List.of(),
                turnPlan,
                ChatPromptSignals.IntentRoute.FACTUAL_TECH,
                false,
                false,
                false,
                false,
                false,
                false
        );

        ChatRagContext directContext = new ChatRagContext(
                List.of(),
                List.of(),
                new ChatGroundingService.GroundingDecision(true, 1.0, 0, 1.0),
                false,
                ChatGroundingService.RagRoute.NO_RAG,
                false,
                false,
                false,
                "fallback",
                RagService.RetrievalStats.empty(List.of(), 0.0, 0, 0.0),
                false
        );
        ChatAssistantOutcome directOutcome = new ChatAssistantOutcome(
                "Respuesta apresurada",
                new ChatGroundingService.GroundingAnswerAssessment(true, 0)
        );

        List<SourceDto> sources = List.of(new SourceDto(3L, 4L, "Runbook", 0.93, "snippet"));
        List<RagService.ScoredChunk> scored = List.of(new RagService.ScoredChunk(null, 0.93));
        ChatRagContext retriedContext = new ChatRagContext(
                scored,
                sources,
                new ChatGroundingService.GroundingDecision(true, 0.93, 2, 0.91),
                true,
                ChatGroundingService.RagRoute.STRONG,
                true,
                false,
                true,
                "fallback"
        );
        ChatAssistantOutcome retriedOutcome = new ChatAssistantOutcome(
                "Respuesta con fuentes [S1]",
                new ChatGroundingService.GroundingAnswerAssessment(true, 1)
        );

        ChatMessage assistantMessage = new ChatMessage();
        ReflectionTestUtils.setField(assistantMessage, "id", 22L);

        when(contextFactory.create("user", null, "Explica el error del endpoint", "auto", null, List.of())).thenReturn(context);
        when(ragFlowService.resolve(context)).thenReturn(directContext);
        when(assistantService.answer(context, directContext)).thenReturn(directOutcome);
        when(decisionEngine.verifyDirectAnswer("Explica el error del endpoint", "Respuesta apresurada", turnPlan))
                .thenReturn(new ChatRagDecisionEngine.AnswerVerification(true, 0.41, "respuesta-incompleta", true));
        when(ragFlowService.resolveForced(context, "respuesta-incompleta")).thenReturn(retriedContext);
        when(assistantService.answer(context, retriedContext)).thenReturn(retriedOutcome);
        when(historyService.saveAssistantMessage(session, "Respuesta con fuentes [S1]")).thenReturn(assistantMessage);

        ChatResponse response = service.chat("user", null, "Explica el error del endpoint", "auto", null, List.of());

        assertTrue(response.isRagUsed());
        assertTrue(response.isRagNeeded());
        assertEquals("Respuesta con fuentes [S1]", response.getReply());
        verify(historyService).persistSources(assistantMessage, scored);
    }

    @Test
    void keepsRespondingWhenPersistingRagSourcesFails() {
        ChatSession session = new ChatSession();
        session.setId("sid-99");
        session.setSystemPrompt(new SystemPrompt());

        ChatMessage userMessage = new ChatMessage();
        ReflectionTestUtils.setField(userMessage, "id", 31L);
        userMessage.setSession(session);
        userMessage.setRole(ChatMessage.Role.USER);
        userMessage.setContent("Que dice el documento?");

        ChatTurnContext context = new ChatTurnContext(
                "user",
                "Que dice el documento?",
                "auto",
                null,
                session,
                userMessage,
                List.of(),
                new ChatTurnPlanner.TurnPlan(
                        ChatPromptSignals.IntentRoute.FACTUAL_TECH,
                        true,
                        ChatTurnPlanner.ReasoningLevel.MEDIUM,
                        false,
                        false,
                        0.85
                ),
                ChatPromptSignals.IntentRoute.FACTUAL_TECH,
                true,
                false,
                false,
                false,
                false,
                false
        );

        List<SourceDto> sources = List.of(new SourceDto(1L, 2L, "Manual", 0.91, "snippet"));
        List<RagService.ScoredChunk> scored = List.of(new RagService.ScoredChunk(null, 0.91));
        ChatRagContext ragContext = new ChatRagContext(
                scored,
                sources,
                new ChatGroundingService.GroundingDecision(true, 0.91, 2, 0.88),
                true,
                ChatGroundingService.RagRoute.STRONG,
                true,
                false,
                true,
                "fallback"
        );
        ChatAssistantOutcome outcome = new ChatAssistantOutcome(
                "Respuesta final [S1]",
                new ChatGroundingService.GroundingAnswerAssessment(true, 1)
        );

        ChatMessage assistantMessage = new ChatMessage();
        ReflectionTestUtils.setField(assistantMessage, "id", 32L);

        when(contextFactory.create("user", null, "Que dice el documento?", "auto", null, List.of())).thenReturn(context);
        when(ragFlowService.resolve(context)).thenReturn(ragContext);
        when(assistantService.answer(context, ragContext)).thenReturn(outcome);
        when(historyService.saveAssistantMessage(session, "Respuesta final [S1]")).thenReturn(assistantMessage);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("fk chat_message_source"))
                .when(historyService).persistSources(assistantMessage, scored);

        ChatResponse response = service.chat("user", null, "Que dice el documento?", "auto", null, List.of());

        assertEquals("sid-99", response.getSessionId());
        assertEquals("Respuesta final [S1]", response.getReply());
        assertTrue(response.isRagUsed());
        verify(sessionService).touchSession(session);
    }
}


