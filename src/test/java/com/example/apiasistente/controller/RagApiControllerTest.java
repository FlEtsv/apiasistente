package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.RagContextStatsDto;
import com.example.apiasistente.model.entity.KnowledgeDocument;
import com.example.apiasistente.service.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RagApiController.class)
@AutoConfigureMockMvc(addFilters = false)
class RagApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
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
