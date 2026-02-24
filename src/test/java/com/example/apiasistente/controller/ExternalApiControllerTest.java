package com.example.apiasistente.controller;

import com.example.apiasistente.model.dto.ChatResponse;
import com.example.apiasistente.model.entity.KnowledgeDocument;
import com.example.apiasistente.service.ChatQueueService;
import com.example.apiasistente.service.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExternalApiController.class)
@AutoConfigureMockMvc(addFilters = false)
class ExternalApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatQueueService chatQueueService;

    @MockBean
    private RagService ragService;

    @Test
    void chatRejectsMissingPrincipal() throws Exception {
        mockMvc.perform(post("/api/ext/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"sid-1","message":"Hola","model":"fast"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void chatDelegatesToQueueInGenericMode() throws Exception {
        ChatResponse response = new ChatResponse("sid-1", "hola", List.of());
        when(chatQueueService.chatAndWait(eq("ext-user"), eq("sid-1"), eq("Hola"), eq("fast"), isNull()))
                .thenReturn(response);

        mockMvc.perform(post("/api/ext/chat")
                        .principal(() -> "ext-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"sid-1","message":"Hola","model":"fast"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sid-1"))
                .andExpect(jsonPath("$.reply").value("hola"));
    }

    @Test
    void chatRejectsSpecialModeWithoutSpecialKey() throws Exception {
        mockMvc.perform(post("/api/ext/chat")
                        .principal(() -> "ext-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"Hola","model":"fast","specialMode":true,"externalUserId":"cli-42"}
                                """))
                .andExpect(status().isForbidden());
    }

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
    void ragPerExternalUserEndpointRequiresSpecialKey() throws Exception {
        mockMvc.perform(post("/api/ext/rag/users/cliente-42/documents")
                        .principal(() -> "ext-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Doc user","content":"Contenido"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void ragPerExternalUserEndpointScopesOwnerByApiKey() throws Exception {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setOwner("key:99|user:cliente-42");
        doc.setTitle("Doc user");
        when(ragService.upsertDocumentForOwner(
                eq("key:99|user:cliente-42"),
                eq("Doc user"),
                eq("Contenido privado")
        )).thenReturn(doc);

        mockMvc.perform(post("/api/ext/rag/users/cliente-42/documents")
                        .principal(() -> "ext-user")
                        .requestAttr("ext.specialModeEnabled", true)
                        .requestAttr("ext.apiKeyId", 99L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Doc user","content":"Contenido privado"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Doc user"));

        verify(ragService).upsertDocumentForOwner(
                eq("key:99|user:cliente-42"),
                eq("Doc user"),
                eq("Contenido privado")
        );
    }
}
