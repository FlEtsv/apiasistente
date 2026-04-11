package com.example.apiasistente.rag.integration;

import com.example.apiasistente.apikey.service.ApiKeyService;
import com.example.apiasistente.rag.entity.KnowledgeDocument;
import com.example.apiasistente.rag.service.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
/**
 * Pruebas de integracion para External Rag Api.
 */
class ExternalRagApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private RagService ragService;

    @Test
    void ragPerExternalUserUsesSameGlobalIngestionWithGenericApiKey() throws Exception {
        stubApiKey("generic-token", 12L, false);

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setOwner(RagService.GLOBAL_OWNER);
        doc.setTitle("Doc user");
        when(ragService.upsertDocument(
                eq("Doc user"),
                eq("Contenido")
        )).thenReturn(doc);

        mockMvc.perform(post("/api/ext/rag/users/cliente-9/documents")
                        .header("X-API-KEY", "generic-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Doc user","content":"Contenido"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Doc user"));
    }

    @Test
    void ragPerExternalUserKeepsWorkingWithSpecialApiKey() throws Exception {
        stubApiKey("special-token", 99L, true);

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setOwner(RagService.GLOBAL_OWNER);
        doc.setTitle("Doc user");
        when(ragService.upsertDocument(
                eq("Doc user"),
                eq("Contenido privado")
        )).thenReturn(doc);

        mockMvc.perform(post("/api/ext/rag/users/cliente-9/documents")
                        .header("X-API-KEY", "special-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Doc user","content":"Contenido privado"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Doc user"));

        verify(ragService).upsertDocument(
                eq("Doc user"),
                eq("Contenido privado")
        );
    }

    private void stubApiKey(String token, long keyId, boolean specialModeEnabled) {
        when(apiKeyService.authenticate(eq(token)))
                .thenReturn(new ApiKeyService.ApiKeyAuthResult(
                        keyId,
                        "ext-user",
                        specialModeEnabled ? "finanzas-special" : "finanzas-generic",
                        specialModeEnabled
                ));
    }
}
