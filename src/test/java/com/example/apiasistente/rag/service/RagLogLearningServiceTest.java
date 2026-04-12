package com.example.apiasistente.rag.service;

import com.example.apiasistente.rag.config.RagLogLearningProperties;
import com.example.apiasistente.rag.entity.KnowledgeDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class RagLogLearningServiceTest {

    @Mock
    private RagService ragService;

    @TempDir
    Path tempDir;

    private RagLogLearningProperties properties;
    private RagLogLearningService service;

    @BeforeEach
    void setUp() {
        properties = new RagLogLearningProperties();
        properties.setEnabled(true);
        properties.setOwner("global");
        properties.setSource("app-log");
        properties.setTags("logs,incident,runtime");
        properties.setTailBytes(32_768);
        properties.setMaxLines(200);
        properties.setMaxChars(10_000);
        properties.setContextLines(2);
        properties.setIncludeOnlyProblematic(true);
        properties.setRedactSecrets(true);
        service = new RagLogLearningService(properties, ragService);

        KnowledgeDocument doc = new KnowledgeDocument();
        lenient().when(ragService.upsertDocumentForOwner(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(doc);
    }

    @Test
    void ingestsProblematicLinesAndRedactsSecrets() throws Exception {
        Path logFile = tempDir.resolve("apiasistente.log");
        Files.writeString(logFile, """
                2026-03-07 10:00:00 INFO Boot completo
                2026-03-07 10:00:01 WARN Memoria alta
                2026-03-07 10:00:02 ERROR Fallo conectando a base de datos
                java.lang.RuntimeException: DB timeout
                Authorization: Bearer abc.def.ghi.jklmnopqrst
                api_key=super-secreto-123
                """, StandardCharsets.UTF_8);
        properties.setPaths(List.of(logFile.toString()));

        int ingested = service.ingestNow();

        assertEquals(1, ingested);
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(ragService).upsertDocumentForOwner(
                eq("global"),
                eq("Runtime Logs :: apiasistente.log"),
                contentCaptor.capture(),
                eq("app-log"),
                eq("logs,incident,runtime")
        );

        String content = contentCaptor.getValue();
        assertTrue(content.contains("ERROR Fallo conectando a base de datos"));
        assertTrue(content.contains("RuntimeException"));
        assertTrue(content.contains("Authorization: Bearer [REDACTED]"));
        assertTrue(content.contains("api_key=[REDACTED]"));
        assertFalse(content.contains("super-secreto-123"));
    }

    @Test
    void skipsUnchangedContentAndReingestsWhenLogChanges() throws Exception {
        Path logFile = tempDir.resolve("trace.log");
        Files.writeString(logFile, """
                2026-03-07 10:00:01 ERROR Primera incidencia
                java.lang.IllegalStateException: primer fallo
                """, StandardCharsets.UTF_8);
        properties.setPaths(List.of(logFile.toString()));

        assertEquals(1, service.ingestNow());
        assertEquals(0, service.ingestNow());

        Files.writeString(logFile, """
                2026-03-07 10:02:01 ERROR Segunda incidencia
                java.lang.IllegalStateException: segundo fallo
                """, StandardCharsets.UTF_8);

        assertEquals(1, service.ingestNow());
        verify(ragService, times(2))
                .upsertDocumentForOwner(eq("global"), eq("Runtime Logs :: trace.log"), anyString(), eq("app-log"), eq("logs,incident,runtime"));
    }

    @Test
    void retriesWhenUpsertFailsWithDeadlockAndEventuallySucceeds() throws Exception {
        Path logFile = tempDir.resolve("deadlock.log");
        Files.writeString(logFile, """
                2026-03-07 10:00:01 ERROR Deadlock found when trying to get lock
                java.sql.SQLException: SQLState: 40001
                """, StandardCharsets.UTF_8);
        properties.setPaths(List.of(logFile.toString()));

        KnowledgeDocument doc = new KnowledgeDocument();
        when(ragService.upsertDocumentForOwner(eq("global"), eq("Runtime Logs :: deadlock.log"), anyString(), eq("app-log"), eq("logs,incident,runtime")))
                .thenThrow(new IllegalStateException("Deadlock found when trying to get lock; try restarting transaction"))
                .thenReturn(doc);

        int ingested = service.ingestNow();

        assertEquals(1, ingested);
        verify(ragService, times(2))
                .upsertDocumentForOwner(eq("global"), eq("Runtime Logs :: deadlock.log"), anyString(), eq("app-log"), eq("logs,incident,runtime"));
    }
}
