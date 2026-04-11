package com.example.apiasistente.rag.controller;

import com.example.apiasistente.rag.entity.KnowledgeDocument;
import com.example.apiasistente.rag.service.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExternalRagController.class)
@AutoConfigureMockMvc(addFilters = false)
/**
 * Pruebas para External Rag Controller.
 */
class ExternalRagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RagService ragService;

    @Test
    void ragDocumentsEndpointStoresGlobalContext() throws Exception {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setOwner(RagService.GLOBAL_OWNER);
        doc.setTitle("Doc global");
        when(ragService.upsertDocument(eq("Doc global"), eq("Contenido global"))).thenReturn(doc);

        mockMvc.perform(post("/api/ext/rag/documents")
                        .principal(() -> "ext-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Doc global","content":"Contenido global"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Doc global"));

        verify(ragService).upsertDocument(eq("Doc global"), eq("Contenido global"));
    }

    @Test
    void ragPerExternalUserEndpointUsesGlobalCorpusAlias() throws Exception {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setOwner(RagService.GLOBAL_OWNER);
        doc.setTitle("Doc user");
        when(ragService.upsertDocument(eq("Doc user"), eq("Contenido"))).thenReturn(doc);

        mockMvc.perform(post("/api/ext/rag/users/cliente-42/documents")
                        .principal(() -> "ext-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Doc user","content":"Contenido"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Doc user"));

        verify(ragService).upsertDocument(eq("Doc user"), eq("Contenido"));
    }

    @Test
    void externalChunkedUpsertUsesStructuredDocumentContract() throws Exception {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setOwner(RagService.GLOBAL_OWNER);
        doc.setTitle("Doc scrapeado");
        when(ragService.upsertStructuredDocumentForOwner(
                eq(RagService.GLOBAL_OWNER),
                eq("Doc scrapeado"),
                eq("fallback opcional"),
                eq("scraper"),
                eq("web"),
                eq("https://externo.test/page"),
                anyList()
        )).thenReturn(doc);

        mockMvc.perform(post("/api/ext/rag/documents")
                        .principal(() -> "ext-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"Doc scrapeado",
                                  "content":"fallback opcional",
                                  "source":"scraper",
                                  "tags":"web",
                                  "url":"https://externo.test/page",
                                  "chunks":[
                                    {"chunkIndex":0,"text":"Chunk externo 1"},
                                    {"chunkIndex":1,"text":"Chunk externo 2"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Doc scrapeado"));
    }
}
