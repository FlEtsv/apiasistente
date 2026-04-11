package com.example.apiasistente.chat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas para Chat Prompt Signals.
 */
class ChatPromptSignalsTest {

    @Test
    void detectsComplexQueries() {
        assertTrue(ChatPromptSignals.isComplexQuery("Compara arquitectura A vs B y explica trade-off de costo y latencia."));
        assertTrue(ChatPromptSignals.isComplexQuery("""
                Necesito un analisis completo:
                1) riesgos
                2) mitigaciones
                3) plan de migracion
                """));
        assertFalse(ChatPromptSignals.isComplexQuery("Que hora es en Madrid?"));
    }

    @Test
    void detectsMultiStepQueries() {
        assertTrue(ChatPromptSignals.isMultiStepQuery("Dame un paso a paso para desplegar en produccion."));
        assertTrue(ChatPromptSignals.isMultiStepQuery("Primero valida el schema, luego genera migraciones y finalmente despliega."));
        assertTrue(ChatPromptSignals.isMultiStepQuery("1) prepara entorno 2) ejecuta pruebas"));
        assertFalse(ChatPromptSignals.isMultiStepQuery("Resume este texto corto."));
    }

    @Test
    void detectsLikelyFiller() {
        assertTrue(ChatPromptSignals.hasLikelyFiller("En resumen, esta es la mejor opcion. Cabe destacar que es flexible."));
        assertTrue(ChatPromptSignals.hasLikelyFiller("""
                Esta solucion funciona bien para tu caso.
                Esta solucion funciona bien para tu caso.
                """));
        assertFalse(ChatPromptSignals.hasLikelyFiller("Instala dependencia, ejecuta test y despliega."));
    }

    @Test
    void routesSmallTalkWithoutRag() {
        assertTrue(ChatPromptSignals.isSmallTalk("Hola"));
        assertTrue(ChatPromptSignals.isSmallTalk("que tal?"));
        assertFalse(ChatPromptSignals.needsRag("Hola"));
        assertFalse(ChatPromptSignals.needsRag("gracias"));
        assertEquals(ChatPromptSignals.IntentRoute.SMALL_TALK, ChatPromptSignals.routeIntent("jaja"));
    }

    @Test
    void detectsTextRenderRequestsAndFormatCorrections() {
        assertTrue(ChatPromptSignals.wantsTextRendering("Dibuja un girasol con caracteres"));
        assertTrue(ChatPromptSignals.wantsTextRendering("Hazme un gato en ASCII"));
        assertTrue(ChatPromptSignals.wantsTextRendering("Que el emoticono quiero caracteres"));
        assertTrue(ChatPromptSignals.isFormatRevision("Lo quiero mas grande"));
        assertTrue(ChatPromptSignals.isFormatRevision("Sin emoji, con caracteres"));
        assertFalse(ChatPromptSignals.needsRag("Puedes dibujar un girasol con caracteres?"));
        assertEquals(
                ChatPromptSignals.IntentRoute.TEXT_RENDER,
                ChatPromptSignals.routeIntent("Si dibuja el girasol con caracter ya")
        );
    }

    @Test
    void routesFactualTechQueriesToRag() {
        assertTrue(ChatPromptSignals.needsRag("Explica mi endpoint /api/ext/chat"));
        assertTrue(ChatPromptSignals.needsRag("Como configuro docker para esta API?"));
        assertTrue(ChatPromptSignals.needsRag("usa el rag"));
        assertTrue(ChatPromptSignals.needsRag("consulta el documento"));
        assertEquals(
                ChatPromptSignals.RagMode.REQUIRED,
                ChatPromptSignals.ragDecision("Explica mi endpoint /api/ext/chat", false).mode()
        );
        assertEquals(
                ChatPromptSignals.RagMode.PREFERRED,
                ChatPromptSignals.ragDecision("Como configuro docker para esta API?", false).mode()
        );
        assertFalse(ChatPromptSignals.isSmallTalk("usa el rag"));
        assertEquals(
                ChatPromptSignals.IntentRoute.FACTUAL_TECH,
                ChatPromptSignals.routeIntent("Que dice el chunk seed-edx?")
        );
    }

    @Test
    void keepsGeneralQuestionsOutOfRag() {
        assertTrue(ChatPromptSignals.needsRag("Como mejoro este correo para un cliente?"));
        assertTrue(ChatPromptSignals.needsRag("Puedes redactarme una propuesta mas clara?"));
        assertEquals(
                ChatPromptSignals.RagMode.PREFERRED,
                ChatPromptSignals.ragDecision("Como mejoro este correo para un cliente?", false).mode()
        );
        assertEquals(
                ChatPromptSignals.IntentRoute.FACTUAL_TECH,
                ChatPromptSignals.routeIntent("Explica paso a paso como ordenar mis ideas para una propuesta")
        );
    }
}

