package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.entity.ChatMessage;
import com.example.apiasistente.chat.entity.ChatSession;
import com.example.apiasistente.chat.service.ChatPromptSignals;
import com.example.apiasistente.chat.service.ChatTurnPlanner;
import com.example.apiasistente.prompt.entity.SystemPrompt;
import com.example.apiasistente.rag.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/**
 * Pruebas para Chat Rag Flow Service.
 */
class ChatRagFlowServiceTest {

    @Mock
    private ChatPromptBuilder promptBuilder;

    @Mock
    private ChatHistoryService historyService;

    @Mock
    private RagService ragService;

    @Mock
    private ChatGroundingService groundingService;

    @Mock
    private ChatRagGateService ragGateService;

    @Mock
    private ChatRagTelemetryService telemetryService;

    private ChatRagFlowService service;

    @BeforeEach
    void setUp() {
        service = new ChatRagFlowService(promptBuilder, historyService, ragService, groundingService, ragGateService, telemetryService);
    }

    @Test
    void returnsExplicitNoEvidenceWhenRagIsRequiredAndRetrievalMisses() {
        ChatTurnContext context = context(
                "Que paso ayer en /api/ext/chat?",
                ChatPromptSignals.RagDecision.required("Contexto propio", List.of("fecha", "ruta-o-archivo"))
        );

        when(promptBuilder.buildRetrievalQuery("Que paso ayer en /api/ext/chat?", List.of(), List.of()))
                .thenReturn("Que paso ayer en /api/ext/chat?");
        when(historyService.recentUserTurnsForRetrieval("sid-1")).thenReturn(List.of());
        when(ragGateService.evaluate(any(ChatTurnPlanner.TurnPlan.class), any(ChatPromptSignals.RagDecision.class), eq("Que paso ayer en /api/ext/chat?"), eq("user"), isNull(), eq(false)))
                .thenReturn(ChatRagGateService.GateDecision.allow("rag-required", List.of("global", "user"), 2, 10, List.of()));
        when(ragService.retrieveForOwnerOrGlobal("Que paso ayer en /api/ext/chat?", "user"))
                .thenReturn(RagService.RetrievalResult.empty(List.of("global", "user"), 1.25, 10, 0.45));
        when(groundingService.noEvidenceMessage()).thenReturn("No encontre evidencia en tu base");

        ChatRagContext result = service.resolve(context);

        assertTrue(result.missingEvidence());
        assertFalse(result.ragUsed());
        assertFalse(result.hasRagContext());
    }

    @Test
    void fallsBackToNormalChatWhenRagIsPreferredAndRetrievalMisses() {
        ChatTurnContext context = context(
                "Compara dos estrategias de cache para Spring Boot",
                ChatPromptSignals.RagDecision.preferred("Consulta tecnica", List.of("consulta-tecnica"))
        );

        when(promptBuilder.buildRetrievalQuery("Compara dos estrategias de cache para Spring Boot", List.of(), List.of()))
                .thenReturn("Compara dos estrategias de cache para Spring Boot");
        when(historyService.recentUserTurnsForRetrieval("sid-1")).thenReturn(List.of());
        when(ragGateService.evaluate(any(ChatTurnPlanner.TurnPlan.class), any(ChatPromptSignals.RagDecision.class), eq("Compara dos estrategias de cache para Spring Boot"), eq("user"), isNull(), eq(false)))
                .thenReturn(ChatRagGateService.GateDecision.allow("preferred-metadata-hit", List.of("global", "user"), 5, 50, List.of("cache")));
        when(ragService.retrieveForOwnerOrGlobal("Compara dos estrategias de cache para Spring Boot", "user"))
                .thenReturn(RagService.RetrievalResult.empty(List.of("global", "user"), 0.95, 10, 0.45));
        when(groundingService.fallbackMessage()).thenReturn("fallback");
        when(groundingService.shouldEnforceGrounding(false)).thenReturn(false);

        ChatRagContext result = service.resolve(context);

        assertFalse(result.missingEvidence());
        assertFalse(result.ragUsed());
        assertFalse(result.hasRagContext());
        assertFalse(result.enforceGrounding());
    }

    @Test
    void skipsPreferredRagBeforeRetrievalWhenGateFindsNoMetadataHints() {
        ChatTurnContext context = context(
                "Compara estrategias de cache",
                ChatPromptSignals.RagDecision.preferred("Consulta tecnica", List.of("consulta-tecnica"))
        );

        when(ragGateService.evaluate(any(ChatTurnPlanner.TurnPlan.class), any(ChatPromptSignals.RagDecision.class), eq("Compara estrategias de cache"), eq("user"), isNull(), eq(false)))
                .thenReturn(ChatRagGateService.GateDecision.skip(
                        "preferred-sin-pistas-metadata",
                        List.of("global", "user"),
                        4,
                        40,
                        List.of("cache", "estrategias"),
                        false
                ));
        when(groundingService.fallbackMessage()).thenReturn("fallback");
        when(groundingService.shouldEnforceGrounding(false)).thenReturn(false);

        ChatRagContext result = service.resolve(context);

        assertFalse(result.missingEvidence());
        assertFalse(result.ragUsed());
        assertFalse(result.hasRagContext());
    }

    @Test
    void requiredRagReturnsNoEvidenceImmediatelyWhenCorpusIsEmpty() {
        ChatTurnContext context = context(
                "Que paso en nuestro endpoint interno?",
                ChatPromptSignals.RagDecision.required("Contexto propio", List.of("contexto-propio"))
        );

        when(ragGateService.evaluate(any(ChatTurnPlanner.TurnPlan.class), any(ChatPromptSignals.RagDecision.class), eq("Que paso en nuestro endpoint interno?"), eq("user"), isNull(), eq(false)))
                .thenReturn(new ChatRagGateService.GateDecision(
                        false,
                        true,
                        "corpus-vacio",
                        List.of("global", "user"),
                        0,
                        0,
                        List.of()
                ));
        when(groundingService.noEvidenceMessage()).thenReturn("No encontre evidencia en tu base");

        ChatRagContext result = service.resolve(context);

        assertTrue(result.missingEvidence());
        assertFalse(result.ragUsed());
        assertFalse(result.hasRagContext());
    }

    private ChatTurnContext context(String userText, ChatPromptSignals.RagDecision ragDecision) {
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
                ragDecision.enabled(),
                ChatTurnPlanner.ReasoningLevel.MEDIUM,
                false,
                false,
                0.8,
                ragDecision
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
                ragDecision.enabled(),
                false,
                false,
                false,
                false,
                false
        );
    }
}
