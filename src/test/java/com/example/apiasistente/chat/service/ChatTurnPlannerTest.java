package com.example.apiasistente.chat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas para Chat Turn Planner.
 */
class ChatTurnPlannerTest {

    private final ChatTurnPlanner planner = new ChatTurnPlanner();

    @Test
    void routesSmallTalkWithoutRagAndLowReasoning() {
        ChatTurnPlanner.TurnPlan plan = planner.plan("hola, gracias", false, false, false);

        assertFalse(plan.ragNeeded());
        assertEquals(ChatTurnPlanner.ReasoningLevel.LOW, plan.reasoningLevel());
        assertEquals(ChatPromptSignals.IntentRoute.SMALL_TALK, plan.intentRoute());
        assertEquals(ChatPromptSignals.RagMode.OFF, plan.ragDecision().mode());
    }

    @Test
    void routesFactualTechQuestionWithRagAndMediumReasoning() {
        ChatTurnPlanner.TurnPlan plan = planner.plan(
                "cual es el endpoint para crear una api key?",
                false,
                false,
                false
        );

        assertTrue(plan.ragNeeded());
        assertEquals(ChatTurnPlanner.ReasoningLevel.MEDIUM, plan.reasoningLevel());
        assertEquals(ChatPromptSignals.IntentRoute.FACTUAL_TECH, plan.intentRoute());
        assertEquals(ChatPromptSignals.RagMode.REQUIRED, plan.ragDecision().mode());
    }

    @Test
    void routesTextRenderWithoutRag() {
        ChatTurnPlanner.TurnPlan plan = planner.plan(
                "dibuja un girasol con caracteres ascii",
                false,
                false,
                false
        );

        assertFalse(plan.ragNeeded());
        assertEquals(ChatTurnPlanner.ReasoningLevel.LOW, plan.reasoningLevel());
        assertEquals(ChatPromptSignals.IntentRoute.TEXT_RENDER, plan.intentRoute());
        assertEquals(ChatPromptSignals.RagMode.OFF, plan.ragDecision().mode());
    }

    @Test
    void routesComplexPromptAsHighReasoning() {
        ChatTurnPlanner.TurnPlan plan = planner.plan(
                "compara dos estrategias de migracion paso a paso y evalua riesgos con un plan de accion",
                false,
                false,
                false
        );

        assertTrue(plan.ragNeeded());
        assertEquals(ChatTurnPlanner.ReasoningLevel.HIGH, plan.reasoningLevel());
        assertTrue(plan.complexQuery() || plan.multiStepQuery());
        assertEquals(ChatPromptSignals.RagMode.PREFERRED, plan.ragDecision().mode());
    }

    @Test
    void keepsRepoSpecificTechnicalLookupsOnRag() {
        ChatTurnPlanner.TurnPlan plan = planner.plan(
                "cual es el endpoint de esta API para crear una api key?",
                false,
                false,
                false
        );

        assertTrue(plan.ragNeeded());
        assertEquals(ChatPromptSignals.IntentRoute.FACTUAL_TECH, plan.intentRoute());
        assertEquals(ChatPromptSignals.RagMode.REQUIRED, plan.ragDecision().mode());
    }

    @Test
    void routesShortImperativeRagRequestsToRag() {
        ChatTurnPlanner.TurnPlan plan = planner.plan(
                "usa el rag",
                false,
                false,
                false
        );

        assertTrue(plan.ragNeeded());
        assertEquals(ChatPromptSignals.IntentRoute.FACTUAL_TECH, plan.intentRoute());
        assertEquals(ChatTurnPlanner.ReasoningLevel.MEDIUM, plan.reasoningLevel());
        assertEquals(ChatPromptSignals.RagMode.PREFERRED, plan.ragDecision().mode());
    }

    @Test
    void routesDocumentQuestionsToRagEvenWithoutTechKeywords() {
        ChatTurnPlanner.TurnPlan plan = planner.plan(
                "Puedes resumir este archivo adjunto para el cliente final?",
                true,
                false,
                true
        );

        assertTrue(plan.ragNeeded());
        assertEquals(ChatTurnPlanner.ReasoningLevel.HIGH, plan.reasoningLevel());
        assertEquals(ChatPromptSignals.IntentRoute.FACTUAL_TECH, plan.intentRoute());
        assertEquals(ChatPromptSignals.RagMode.REQUIRED, plan.ragDecision().mode());
    }
}

