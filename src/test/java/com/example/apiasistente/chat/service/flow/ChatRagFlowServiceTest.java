package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.entity.ChatMessage;
import com.example.apiasistente.chat.entity.ChatSession;
import com.example.apiasistente.chat.service.ChatPromptSignals;
import com.example.apiasistente.chat.service.ChatTurnPlanner;
import com.example.apiasistente.rag.entity.KnowledgeChunk;
import com.example.apiasistente.rag.entity.KnowledgeDocument;
import com.example.apiasistente.prompt.entity.SystemPrompt;
import com.example.apiasistente.rag.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        ReflectionTestUtils.setField(service, "overrideGateForUncertainPreferred", true);
        ReflectionTestUtils.setField(service, "minQueryCharsForGateOverride", 14);
        ReflectionTestUtils.setField(service, "secondPassOnEmptyEnabled", true);
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
        when(ragGateService.evaluate(any(ChatTurnPlanner.TurnPlan.class), any(ChatPromptSignals.RagDecision.class), eq("Que paso ayer en /api/ext/chat?"), eq("user"), eq(null), eq(false)))
                .thenReturn(ChatRagGateService.GateDecision.allow("rag-required", List.of("global"), 2, 10, List.of()));
        when(ragService.retrieveShared("Que paso ayer en /api/ext/chat?"))
                .thenReturn(RagService.RetrievalResult.empty(List.of("global"), 1.25, 10, 0.45));
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
        when(ragGateService.evaluate(any(ChatTurnPlanner.TurnPlan.class), any(ChatPromptSignals.RagDecision.class), eq("Compara dos estrategias de cache para Spring Boot"), eq("user"), eq(null), eq(false)))
                .thenReturn(ChatRagGateService.GateDecision.allow("preferred-metadata-hit", List.of("global"), 5, 50, List.of("cache")));
        when(ragService.retrieveShared("Compara dos estrategias de cache para Spring Boot"))
                .thenReturn(RagService.RetrievalResult.empty(List.of("global"), 0.95, 10, 0.45));
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

        when(ragGateService.evaluate(any(ChatTurnPlanner.TurnPlan.class), any(ChatPromptSignals.RagDecision.class), eq("Compara estrategias de cache"), eq("user"), eq(null), eq(false)))
                .thenReturn(ChatRagGateService.GateDecision.skip(
                        "preferred-sin-pistas-metadata",
                        List.of("global"),
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

        when(ragGateService.evaluate(any(ChatTurnPlanner.TurnPlan.class), any(ChatPromptSignals.RagDecision.class), eq("Que paso en nuestro endpoint interno?"), eq("user"), eq(null), eq(false)))
                .thenReturn(new ChatRagGateService.GateDecision(
                        false,
                        true,
                        "corpus-vacio",
                        List.of("global"),
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

    @Test
    void requiredRagFailsClosedWhenRetrievalThrows() {
        ChatTurnContext context = context(
                "Explica el incidente del endpoint",
                ChatPromptSignals.RagDecision.required("Contexto interno", List.of("endpoint", "incidente"))
        );

        when(promptBuilder.buildRetrievalQuery("Explica el incidente del endpoint", List.of(), List.of()))
                .thenReturn("Explica el incidente del endpoint");
        when(historyService.recentUserTurnsForRetrieval("sid-1")).thenReturn(List.of());
        when(ragGateService.evaluate(any(ChatTurnPlanner.TurnPlan.class), any(ChatPromptSignals.RagDecision.class), eq("Explica el incidente del endpoint"), eq("user"), eq(null), eq(false)))
                .thenReturn(ChatRagGateService.GateDecision.allow("rag-required", List.of("global"), 3, 20, List.of("endpoint")));
        when(ragService.retrieveShared("Explica el incidente del endpoint"))
                .thenThrow(new IllegalStateException("HNSW no disponible"));
        when(groundingService.retrievalUnavailableMessage()).thenReturn("RAG caido");

        ChatRagContext result = service.resolve(context);

        assertTrue(result.missingEvidence());
        assertFalse(result.ragUsed());
        assertFalse(result.hasRagContext());
        assertEquals("RAG caido", result.fallbackMessage());
    }

    @Test
    void preferredRagDegradesExplicitlyWhenRetrievalThrows() {
        ChatTurnContext context = context(
                "Resume la documentacion del adaptador",
                ChatPromptSignals.RagDecision.preferred("Consulta tecnica", List.of("adaptador"))
        );

        when(promptBuilder.buildRetrievalQuery("Resume la documentacion del adaptador", List.of(), List.of()))
                .thenReturn("Resume la documentacion del adaptador");
        when(historyService.recentUserTurnsForRetrieval("sid-1")).thenReturn(List.of());
        when(ragGateService.evaluate(any(ChatTurnPlanner.TurnPlan.class), any(ChatPromptSignals.RagDecision.class), eq("Resume la documentacion del adaptador"), eq("user"), eq(null), eq(false)))
                .thenReturn(ChatRagGateService.GateDecision.allow("preferred-metadata-hit", List.of("global"), 3, 20, List.of("adaptador")));
        when(ragService.retrieveShared("Resume la documentacion del adaptador"))
                .thenThrow(new IllegalStateException("embed timeout"));
        when(groundingService.retrievalUnavailableMessage()).thenReturn("RAG temporalmente no disponible");

        ChatRagContext result = service.resolve(context);

        assertTrue(result.missingEvidence());
        assertFalse(result.ragUsed());
        assertFalse(result.hasRagContext());
        assertEquals("RAG temporalmente no disponible", result.fallbackMessage());
    }

    @Test
    void retriesWithUserOnlyQueryWhenExpandedRetrievalMisses() {
        ChatTurnContext context = context(
                "Resume la estrategia de cache para monitor",
                ChatPromptSignals.RagDecision.preferred("Consulta tecnica", List.of("cache", "monitor"))
        );
        String expandedQuery = "Resume la estrategia de cache para monitor\n\nContexto reciente del usuario:\n- hablame de monitor";
        ChatRagDecisionEngine.DecisionAssessment uncertainAssessment = new ChatRagDecisionEngine.DecisionAssessment(
                ChatRagDecisionEngine.QueryType.TECHNICAL,
                true,
                true,
                0.62,
                0.62,
                false,
                false,
                true,
                "heuristic",
                "llm-no-disponible"
        );
        ChatRagGateService.GateDecision gateDecision = new ChatRagGateService.GateDecision(
                true,
                false,
                "preferred-metadata-hit",
                List.of("global"),
                12,
                120,
                List.of("cache"),
                uncertainAssessment
        );
        RagService.RetrievalResult miss = RagService.RetrievalResult.empty(List.of("global"), 1.10, 10, 0.45);
        RagService.RetrievalResult hit = retrievalWithEvidence("Documento Cache", "Usa cache Caffeine con TTL corto", 0.83);
        ChatGroundingService.GroundingDecision groundingDecision =
                new ChatGroundingService.GroundingDecision(true, 0.84, 1, 0.83);

        when(historyService.recentUserTurnsForRetrieval("sid-1"))
                .thenReturn(List.of(userTurn("turno inicial"), userTurn("hablame de monitor")));
        when(promptBuilder.buildRetrievalQuery(eq("Resume la estrategia de cache para monitor"), any(), eq(List.of())))
                .thenReturn(expandedQuery);
        when(ragGateService.evaluate(any(ChatTurnPlanner.TurnPlan.class), any(ChatPromptSignals.RagDecision.class), eq("Resume la estrategia de cache para monitor"), eq("user"), eq(null), eq(false)))
                .thenReturn(gateDecision);
        when(ragService.retrieveShared(expandedQuery)).thenReturn(miss);
        when(ragService.retrieveShared("Resume la estrategia de cache para monitor")).thenReturn(hit);
        when(groundingService.assessGrounding(any())).thenReturn(groundingDecision);
        when(groundingService.resolveRagRoute(true, groundingDecision)).thenReturn(ChatGroundingService.RagRoute.STRONG);
        when(groundingService.shouldEnforceGrounding(true)).thenReturn(true);
        when(groundingService.fallbackMessage()).thenReturn("fallback");

        ChatRagContext result = service.resolve(context);

        assertTrue(result.ragUsed());
        assertTrue(result.hasRagContext());
        assertFalse(result.missingEvidence());
        verify(ragService, times(1)).retrieveShared(expandedQuery);
        verify(ragService, times(1)).retrieveShared("Resume la estrategia de cache para monitor");
    }

    @Test
    void overridesPreferredGateSkipWhenDecisionIsUncertain() {
        ChatTurnContext context = context(
                "Compara estrategias de cache para endpoint interno",
                ChatPromptSignals.RagDecision.preferred("Consulta tecnica", List.of("cache", "endpoint"))
        );
        ChatRagDecisionEngine.DecisionAssessment uncertainSkip = new ChatRagDecisionEngine.DecisionAssessment(
                ChatRagDecisionEngine.QueryType.TECHNICAL,
                false,
                false,
                0.58,
                0.58,
                false,
                false,
                true,
                "heuristic",
                "decision-incierta"
        );

        when(historyService.recentUserTurnsForRetrieval("sid-1")).thenReturn(List.of());
        when(promptBuilder.buildRetrievalQuery("Compara estrategias de cache para endpoint interno", List.of(), List.of()))
                .thenReturn("Compara estrategias de cache para endpoint interno");
        when(ragGateService.evaluate(any(ChatTurnPlanner.TurnPlan.class), any(ChatPromptSignals.RagDecision.class), eq("Compara estrategias de cache para endpoint interno"), eq("user"), eq(null), eq(false)))
                .thenReturn(ChatRagGateService.GateDecision.skip(
                        "preferred-sin-pistas-metadata",
                        List.of("global"),
                        8,
                        80,
                        List.of("cache"),
                        false,
                        uncertainSkip
                ));
        when(ragService.retrieveShared("Compara estrategias de cache para endpoint interno"))
                .thenReturn(RagService.RetrievalResult.empty(List.of("global"), 0.90, 10, 0.45));
        when(groundingService.fallbackMessage()).thenReturn("fallback");
        when(groundingService.shouldEnforceGrounding(false)).thenReturn(false);

        ChatRagContext result = service.resolve(context);

        assertFalse(result.ragUsed());
        assertFalse(result.hasRagContext());
        assertFalse(result.missingEvidence());
        verify(ragService, times(1)).retrieveShared("Compara estrategias de cache para endpoint interno");
    }

    private RagService.RetrievalResult retrievalWithEvidence(String title, String chunkText, double score) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setTitle(title);
        document.setOwner("global");
        document.setSource("test");

        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setDocument(document);
        chunk.setChunkIndex(0);
        chunk.setText(chunkText);
        chunk.setHash("hash-test");
        chunk.setTokenCount(12);

        RagService.ScoredChunk scored = new RagService.ScoredChunk(chunk, score, chunkText);
        return new RagService.RetrievalResult(
                List.of(scored),
                List.of(scored),
                new RagService.RetrievalStats(
                        List.of("global"),
                        0.60,
                        10,
                        1,
                        score,
                        score,
                        0.45,
                        24,
                        List.of(1L),
                        List.of(title)
                )
        );
    }

    private ChatMessage userTurn(String text) {
        ChatMessage message = new ChatMessage();
        message.setRole(ChatMessage.Role.USER);
        message.setContent(text);
        return message;
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
                ChatPromptSignals.captureIntent(userText),
                ragDecision.enabled(),
                false,
                false,
                false,
                false,
                false
        );
    }
}
