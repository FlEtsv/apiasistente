package com.example.apiasistente.rag.controller;

import com.example.apiasistente.rag.dto.RagContextStatsDto;
import com.example.apiasistente.rag.entity.KnowledgeDocument;
import com.example.apiasistente.rag.service.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RagApiController.class)
@AutoConfigureMockMvc(addFilters = false)
/**
 * Pruebas para Rag Api Controller.
 */
class RagApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RagService ragService;

    @Test
    void statsReturnsAggregatedContext() throws Exception {
        RagContextStatsDto stats = new RagContextStatsDto(
                "user-a",
                9,
                42,
                4,
                20,
                5,
                22,
                Instant.parse("2026-02-23T18:30:00Z"),
                5,
                900,
                150
        );
        when(ragService.contextStatsForOwnerOrGlobal(eq("user-a"))).thenReturn(stats);

        mockMvc.perform(get("/api/rag/stats").principal(() -> "user-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value("user-a"))
                .andExpect(jsonPath("$.totalDocuments").value(9))
                .andExpect(jsonPath("$.totalChunks").value(42))
                .andExpect(jsonPath("$.globalDocuments").value(4))
                .andExpect(jsonPath("$.globalChunks").value(20))
                .andExpect(jsonPath("$.ownerDocuments").value(5))
                .andExpect(jsonPath("$.ownerChunks").value(22))
                .andExpect(jsonPath("$.topK").value(5))
                .andExpect(jsonPath("$.chunkSize").value(900))
                .andExpect(jsonPath("$.chunkOverlap").value(150));
    }

    @Test
    void userScopedUpsertAllowsSamePrincipal() throws Exception {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setOwner("user-a");
        doc.setTitle("Doc privado");
        when(ragService.upsertDocumentForOwner(eq("user-a"), eq("Doc privado"), eq("Contenido privado")))
                .thenReturn(doc);

        mockMvc.perform(post("/api/rag/users/user-a/documents")
                        .principal(() -> "user-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Doc privado","content":"Contenido privado"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Doc privado"));
    }

    @Test
    void globalUpsertStoresSharedDocument() throws Exception {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setOwner(RagService.GLOBAL_OWNER);
        doc.setTitle("Doc global");
        when(ragService.upsertDocument(eq("Doc global"), eq("Contenido global"))).thenReturn(doc);

        mockMvc.perform(post("/api/rag/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Doc global","content":"Contenido global"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Doc global"));
    }

    @Test
    void chunkedUpsertUsesStructuredIngestionForScraper() throws Exception {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setOwner(RagService.GLOBAL_OWNER);
        doc.setTitle("Doc scrapeado");
        when(ragService.upsertStructuredDocumentForOwner(
                eq(RagService.GLOBAL_OWNER),
                eq("Doc scrapeado"),
                eq("Contenido legacy opcional"),
                eq("scraper"),
                eq("web,faq"),
                eq("https://acme.test/docs/faq"),
                anyList()
        )).thenReturn(doc);

        mockMvc.perform(post("/api/rag/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"Doc scrapeado",
                                  "content":"Contenido legacy opcional",
                                  "source":"scraper",
                                  "tags":"web,faq",
                                  "url":"https://acme.test/docs/faq",
                                  "chunks":[
                                    {"chunkIndex":0,"text":"Primer chunk limpio","tokenCount":3},
                                    {"chunkIndex":1,"text":"Segundo chunk limpio","tokenCount":3}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Doc scrapeado"));
    }

    @Test
    void globalBatchUpsertStoresAllDocuments() throws Exception {
        KnowledgeDocument first = new KnowledgeDocument();
        first.setTitle("Doc 1");
        KnowledgeDocument second = new KnowledgeDocument();
        second.setTitle("Doc 2");
        when(ragService.upsertDocument(eq("Doc 1"), eq("Contenido 1"))).thenReturn(first);
        when(ragService.upsertDocument(eq("Doc 2"), eq("Contenido 2"))).thenReturn(second);

        mockMvc.perform(post("/api/rag/documents/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {"title":"Doc 1","content":"Contenido 1"},
                                  {"title":"Doc 2","content":"Contenido 2"}
                                ]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Doc 1"))
                .andExpect(jsonPath("$[1].title").value("Doc 2"));
    }

    @Test
    void userScopedBatchUpsertAllowsSamePrincipal() throws Exception {
        KnowledgeDocument first = new KnowledgeDocument();
        first.setTitle("Doc 1");
        KnowledgeDocument second = new KnowledgeDocument();
        second.setTitle("Doc 2");
        when(ragService.upsertDocumentForOwner(eq("user-a"), eq("Doc 1"), eq("Contenido 1"))).thenReturn(first);
        when(ragService.upsertDocumentForOwner(eq("user-a"), eq("Doc 2"), eq("Contenido 2"))).thenReturn(second);

        mockMvc.perform(post("/api/rag/users/user-a/documents/batch")
                        .principal(() -> "user-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {"title":"Doc 1","content":"Contenido 1"},
                                  {"title":"Doc 2","content":"Contenido 2"}
                                ]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Doc 1"))
                .andExpect(jsonPath("$[1].title").value("Doc 2"));
    }

    @Test
    void memoryStoresDocumentForPrincipal() throws Exception {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTitle("Memoria");
        when(ragService.storeMemory(eq("user-a"), eq("Memoria"), eq("Hecho importante"))).thenReturn(doc);

        mockMvc.perform(post("/api/rag/memory")
                        .principal(() -> "user-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Memoria","content":"Hecho importante"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Memoria"));
    }

    @Test
    void userScopedUpsertRejectsDifferentPrincipal() throws Exception {
        mockMvc.perform(post("/api/rag/users/user-a/documents")
                        .principal(() -> "user-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Doc privado","content":"Contenido privado"}
                                """))
                .andExpect(status().isForbidden());
    }
}


