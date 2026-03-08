package com.example.apiasistente.rag.service;

import com.example.apiasistente.rag.config.RagCodeLearningProperties;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagCodeLearningServiceTest {

    @Mock
    private RagService ragService;

    @TempDir
    Path tempDir;

    private RagCodeLearningProperties properties;
    private RagCodeLearningService service;

    @BeforeEach
    void setUp() {
        properties = new RagCodeLearningProperties();
        properties.setEnabled(true);
        properties.setRootPath(tempDir.toString());
        properties.setScanPaths(List.of("src"));
        properties.setExcludeDirectories(List.of("build", ".git", "data"));
        properties.setIncludeExtensions(List.of(".java", ".js"));
        properties.setMaxFilesPerRun(10);
        properties.setMaxFileSizeBytes(200_000);
        properties.setMaxCharsPerFile(10_000);
        properties.setMaxMethodLines(40);
        properties.setMaxLineLength(120);
        properties.setMaxRecommendations(5);

        service = new RagCodeLearningService(properties, ragService);
        when(ragService.upsertDocumentForOwner(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new KnowledgeDocument());
    }

    @Test
    void ingestsCodeWithAutomaticRecommendations() throws Exception {
        Path source = tempDir.resolve("src/AppService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package demo;
                public class AppService {
                    // TODO revisar timeout
                    public String run() {
                        try {
                            return "ok";
                        } catch (Exception ex) {
                            return ex.getMessage();
                        }
                    }
                }
                """, StandardCharsets.UTF_8);

        int ingested = service.ingestNow();

        assertEquals(1, ingested);
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(ragService).upsertDocumentForOwner(
                eq("global"),
                eq("Codebase :: src/AppService.java"),
                contentCaptor.capture(),
                eq("code-learning"),
                eq("code,review,architecture,ext:java")
        );

        String content = contentCaptor.getValue();
        assertTrue(content.contains("Resumen automatico"));
        assertTrue(content.contains("TODO/FIXME"));
        assertTrue(content.contains("Catch generico"));
        assertTrue(content.contains("```java"));
    }

    @Test
    void skipsUnchangedFileAndReingestsAfterChange() throws Exception {
        Path source = tempDir.resolve("src/Worker.js");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                export function runJob() {
                  return 'ok';
                }
                """, StandardCharsets.UTF_8);

        assertEquals(1, service.ingestNow());
        assertEquals(0, service.ingestNow());

        Files.writeString(source, """
                export function runJob() {
                  // FIXME revisar retries
                  return 'ok';
                }
                """, StandardCharsets.UTF_8);

        assertEquals(1, service.ingestNow());
        verify(ragService, times(2))
                .upsertDocumentForOwner(eq("global"), eq("Codebase :: src/Worker.js"), anyString(), eq("code-learning"), anyString());
    }
}
