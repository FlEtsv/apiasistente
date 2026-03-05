package com.example.apiasistente.chat.service;

import com.example.apiasistente.shared.config.OllamaProperties;
import com.example.apiasistente.chat.dto.ChatResponse;
import com.example.apiasistente.auth.entity.AppUser;
import com.example.apiasistente.chat.entity.ChatMessage;
import com.example.apiasistente.chat.entity.ChatSession;
import com.example.apiasistente.prompt.entity.SystemPrompt;
import com.example.apiasistente.prompt.service.SystemPromptService;
import com.example.apiasistente.auth.repository.AppUserRepository;
import com.example.apiasistente.chat.config.ChatProcessRouterProperties;
import com.example.apiasistente.chat.dto.ChatMediaInput;
import com.example.apiasistente.chat.config.ChatAuditProperties;
import com.example.apiasistente.chat.repository.ChatMessageRepository;
import com.example.apiasistente.chat.repository.ChatMessageSourceRepository;
import com.example.apiasistente.chat.repository.ChatSessionRepository;
import com.example.apiasistente.chat.service.flow.ChatAssistantService;
import com.example.apiasistente.chat.service.flow.ChatGroundingService;
import com.example.apiasistente.chat.service.flow.ChatHistoryService;
import com.example.apiasistente.chat.service.flow.ChatMediaService;
import com.example.apiasistente.chat.service.flow.ChatPromptBuilder;
import com.example.apiasistente.chat.service.flow.ChatRagDecisionEngine;
import com.example.apiasistente.chat.service.flow.ChatRagFlowService;
import com.example.apiasistente.chat.service.flow.ChatRagGateService;
import com.example.apiasistente.chat.service.flow.ChatRagTelemetryService;
import com.example.apiasistente.chat.service.flow.ChatSessionService;
import com.example.apiasistente.chat.service.flow.ChatSourceSnapshotService;
import com.example.apiasistente.chat.service.flow.ChatTurnContextFactory;
import com.example.apiasistente.chat.service.flow.ChatTurnService;
import com.example.apiasistente.chat.service.flow.ChatImageGenerationService;
import com.example.apiasistente.rag.service.RagService;
import com.example.apiasistente.rag.repository.KnowledgeChunkRepository;
import com.example.apiasistente.rag.repository.KnowledgeDocumentRepository;
import com.example.apiasistente.shared.ai.OllamaClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/**
 * Pruebas para Chat Service.
 */
class ChatServiceTest {

    @Mock
    private ChatSessionRepository sessionRepo;

    @Mock
    private ChatMessageRepository messageRepo;

    @Mock
    private ChatMessageSourceRepository sourceRepo;

    @Mock
    private SystemPromptService promptService;

    @Mock
    private RagService ragService;

    @Mock
    private OllamaClient ollama;

    @Mock
    private AppUserRepository userRepo;

    @Mock
    private KnowledgeDocumentRepository documentRepository;

    @Mock
    private KnowledgeChunkRepository chunkRepository;

    @Mock
    private ChatImageGenerationService imageGenerationService;

    private ChatService service;

    @BeforeEach
    void setUp() {
        OllamaProperties props = new OllamaProperties();
        props.setChatModel("chat-model");
        props.setFastChatModel("fast-model");
        props.setResponseGuardModel("");

        ChatModelSelector modelSelector = new ChatModelSelector(props);
        ChatProcessRouterProperties processRouterProperties = new ChatProcessRouterProperties();
        processRouterProperties.setLlmAssessmentEnabled(false);
        ChatProcessRouter processRouter = new ChatProcessRouter(modelSelector, ollama, processRouterProperties);
        ChatAuditProperties auditProperties = new ChatAuditProperties();
        auditProperties.setEnabled(false);
        ChatAuditTrailService auditTrailService = new ChatAuditTrailService(auditProperties);
        ChatSessionService sessionService = new ChatSessionService(sessionRepo, promptService, userRepo);
        ChatHistoryService historyService = new ChatHistoryService(messageRepo, sourceRepo, sessionService);
        ChatPromptBuilder promptBuilder = new ChatPromptBuilder(modelSelector);
        ChatMediaService mediaService = new ChatMediaService(ollama, modelSelector);
        ChatGroundingService groundingService = new ChatGroundingService(ollama, modelSelector);
        ChatRagDecisionEngine decisionEngine = new ChatRagDecisionEngine(ollama, modelSelector);
        ChatRagTelemetryService ragTelemetryService = new ChatRagTelemetryService();
        ChatRagGateService ragGateService = new ChatRagGateService(documentRepository, chunkRepository, decisionEngine);
        ChatSourceSnapshotService sourceSnapshotService = new ChatSourceSnapshotService(messageRepo, sourceRepo);
        ChatTurnContextFactory contextFactory = new ChatTurnContextFactory(
                sessionService,
                historyService,
                mediaService,
                new ChatTurnPlanner()
        );
        ChatRagFlowService ragFlowService = new ChatRagFlowService(
                promptBuilder,
                historyService,
                ragService,
                groundingService,
                ragGateService,
                ragTelemetryService
        );
        ChatAssistantService assistantService = new ChatAssistantService(
                ollama,
                promptBuilder,
                mediaService,
                groundingService,
                historyService
        );
        ChatTurnService turnService = new ChatTurnService(
                contextFactory,
                ragFlowService,
                assistantService,
                historyService,
                sourceSnapshotService,
                sessionService,
                decisionEngine,
                ragTelemetryService,
                auditTrailService
        );

        service = new ChatService(
                turnService,
                imageGenerationService,
                processRouter,
                auditTrailService,
                sessionService,
                historyService,
                ragTelemetryService
        );

        ReflectionTestUtils.setField(historyService, "maxHistory", 6);
        ReflectionTestUtils.setField(historyService, "retrievalUserTurns", 3);
        ReflectionTestUtils.setField(groundingService, "responseGuardEnabled", true);
        ReflectionTestUtils.setField(ragGateService, "gateEnabled", false);
        ReflectionTestUtils.setField(decisionEngine, "decisionEnabled", false);
        ReflectionTestUtils.setField(ragTelemetryService, "maxEvents", 40);

        lenient().when(messageRepo.getReferenceById(anyLong())).thenAnswer(invocation -> {
            ChatMessage ref = new ChatMessage();
            ReflectionTestUtils.setField(ref, "id", invocation.getArgument(0));
            return ref;
        });
    }

    @Test
    void usesDirectCharacterRenderPromptAndPrimaryModel() {
        AppUser user = new AppUser();
        user.setId(7L);
        user.setUsername("user");

        SystemPrompt prompt = new SystemPrompt();
        prompt.setName("default");
        prompt.setContent("Eres un asistente util.");

        ChatSession session = new ChatSession();
        session.setId("sid-77");
        session.setUser(user);
        session.setSystemPrompt(prompt);
        session.setTitle("Chat previo");
        session.setLastActivityAt(Instant.parse("2026-02-27T10:00:00Z"));

        ChatMessage prevUser = new ChatMessage();
        ReflectionTestUtils.setField(prevUser, "id", 100L);
        prevUser.setSession(session);
        prevUser.setRole(ChatMessage.Role.USER);
        prevUser.setContent("Dibuja un girasol");

        ChatMessage prevAssistant = new ChatMessage();
        ReflectionTestUtils.setField(prevAssistant, "id", 101L);
        prevAssistant.setSession(session);
        prevAssistant.setRole(ChatMessage.Role.ASSISTANT);
        prevAssistant.setContent("\uD83C\uDF3B");

        when(userRepo.findByUsername("user")).thenReturn(Optional.of(user));
        when(sessionRepo.findFirstByUser_IdAndExternalUserIdIsNullOrderByLastActivityAtDesc(7L))
                .thenReturn(Optional.of(session));
        when(sessionRepo.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepo.findRecentForContext(eq("sid-77"), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(prevAssistant, prevUser));

        AtomicLong nextId = new AtomicLong(1L);
        when(messageRepo.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", nextId.getAndIncrement());
            return saved;
        });

        AtomicReference<List<OllamaClient.Message>> capturedMessages = new AtomicReference<>();
        AtomicReference<String> capturedModel = new AtomicReference<>();
        when(ollama.chat(anyList(), anyString())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<OllamaClient.Message> messages = invocation.getArgument(0, List.class);
            capturedMessages.set(messages);
            capturedModel.set(invocation.getArgument(1, String.class));
            return """
                    /\\_/\\\\
                   ( o.o )
                    > ^ <
                    """;
        });

        ChatResponse response = service.chat(
                "user",
                null,
                "Que el emoticono quiero caracteres",
                "auto"
        );

        assertEquals("chat-model", capturedModel.get());
        assertFalse(response.isRagNeeded());
        assertFalse(response.isRagUsed());
        assertEquals(
                """
                        /\\_/\\\\
                       ( o.o )
                        > ^ <
                        """.trim(),
                response.getReply().trim()
        );

        List<OllamaClient.Message> messages = capturedMessages.get();
        assertEquals(4, messages.size());
        assertEquals("Dibuja un girasol", messages.get(1).content());
        assertEquals("\uD83C\uDF3B", messages.get(2).content());

        String finalPrompt = messages.get(3).content();
        assertTrue(finalPrompt.contains("Modo ejecucion directa"));
        assertTrue(finalPrompt.contains("Modo dibujo/texto"));
        assertTrue(finalPrompt.contains("No uses emojis ni placeholders visuales."));
        assertTrue(finalPrompt.contains("sin pedir confirmacion"));
        assertTrue(finalPrompt.contains("Mensaje del usuario: Que el emoticono quiero caracteres"));

        verifyNoInteractions(ragService, sourceRepo);
    }

    @Test
    void usesExecutionPromptForGeneralTasksWithoutRag() {
        AppUser user = new AppUser();
        user.setId(9L);
        user.setUsername("user");

        SystemPrompt prompt = new SystemPrompt();
        prompt.setName("default");
        prompt.setContent("Eres un asistente util.");

        ChatSession session = new ChatSession();
        session.setId("sid-99");
        session.setUser(user);
        session.setSystemPrompt(prompt);
        session.setTitle("Nuevo chat");
        session.setLastActivityAt(Instant.parse("2026-02-27T10:00:00Z"));

        when(userRepo.findByUsername("user")).thenReturn(Optional.of(user));
        when(sessionRepo.findFirstByUser_IdAndExternalUserIdIsNullOrderByLastActivityAtDesc(9L))
                .thenReturn(Optional.of(session));
        when(sessionRepo.save(any(ChatSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepo.findRecentForContext(eq("sid-99"), anyLong(), any(Pageable.class)))
                .thenReturn(List.of());

        AtomicLong nextId = new AtomicLong(1L);
        when(messageRepo.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", nextId.getAndIncrement());
            return saved;
        });

        AtomicReference<List<OllamaClient.Message>> capturedMessages = new AtomicReference<>();
        AtomicReference<String> capturedModel = new AtomicReference<>();
        when(ollama.chat(anyList(), anyString())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<OllamaClient.Message> messages = invocation.getArgument(0, List.class);
            capturedMessages.set(messages);
            capturedModel.set(invocation.getArgument(1, String.class));
            return "Asunto: Reunion\n\nHola, me gustaria coordinar una reunion el martes.";
        });

        ChatResponse response = service.chat(
                "user",
                null,
                "Redacta un correo breve para pedir una reunion el martes",
                "auto"
        );

        assertEquals("fast-model", capturedModel.get());
        assertFalse(response.isRagNeeded());
        assertFalse(response.isRagUsed());
        assertTrue(response.getReply().contains("Asunto: Reunion"));

        List<OllamaClient.Message> messages = capturedMessages.get();
        assertEquals(2, messages.size());

        String finalPrompt = messages.get(1).content();
        assertTrue(finalPrompt.contains("entrega el resultado completo en esta misma respuesta"));
        assertTrue(finalPrompt.contains("Modo ejecucion directa"));
        assertTrue(finalPrompt.contains("Mensaje del usuario: Redacta un correo breve para pedir una reunion el martes"));

        verifyNoInteractions(ragService, sourceRepo);
    }

    @Test
    void routesImageAliasToImageGenerationService() {
        ChatResponse imageResponse = new ChatResponse("sid-img", "Imagen lista", List.of());
        when(imageGenerationService.generate("user", null, "gato astronauta", "image", null, List.of()))
                .thenReturn(imageResponse);

        ChatResponse response = service.chat("user", null, "gato astronauta", "image");

        assertEquals("sid-img", response.getSessionId());
        assertEquals("Imagen lista", response.getReply());
        verify(imageGenerationService).generate("user", null, "gato astronauta", "image", null, List.of());
    }

    @Test
    void routesAutoImagePromptToImageGenerationService() {
        ChatResponse imageResponse = new ChatResponse("sid-img", "Imagen auto", List.of());
        when(imageGenerationService.generate(
                "user",
                null,
                "Genera una imagen de un gato astronauta en marte",
                "image",
                null,
                List.of()
        )).thenReturn(imageResponse);

        ChatResponse response = service.chat(
                "user",
                null,
                "Genera una imagen de un gato astronauta en marte",
                "auto"
        );

        assertEquals("sid-img", response.getSessionId());
        assertEquals("Imagen auto", response.getReply());
        verify(imageGenerationService).generate(
                "user",
                null,
                "Genera una imagen de un gato astronauta en marte",
                "image",
                null,
                List.of()
        );
    }

    @Test
    void routesCheckpointModelToImageGenerationService() {
        ChatResponse imageResponse = new ChatResponse("sid-img", "Imagen HQ lista", List.of());
        when(imageGenerationService.generate(
                "user",
                null,
                "retrato cinematografico",
                "flux1-dev-fp8.safetensors",
                null,
                List.of()
        )).thenReturn(imageResponse);

        ChatResponse response = service.chat("user", null, "retrato cinematografico", "flux1-dev-fp8.safetensors");

        assertEquals("sid-img", response.getSessionId());
        assertEquals("Imagen HQ lista", response.getReply());
        verify(imageGenerationService).generate(
                "user",
                null,
                "retrato cinematografico",
                "flux1-dev-fp8.safetensors",
                null,
                List.of()
        );
    }

    @Test
    void routesImageToImageWhenAutoAndCameraPhotoIsUsedForTransformation() {
        ChatMediaInput cameraPhoto = new ChatMediaInput();
        cameraPhoto.setName("camera.jpg");
        cameraPhoto.setMimeType("image/jpeg");
        cameraPhoto.setBase64("ZmFrZS1pbWFnZS1iYXNlNjQ=");

        ChatResponse imageResponse = new ChatResponse("sid-img", "Imagen transformada", List.of());
        when(imageGenerationService.generate(
                "user",
                null,
                "Crea otra version basada en esta imagen",
                "image",
                null,
                List.of(cameraPhoto)
        )).thenReturn(imageResponse);

        ChatResponse response = service.chat(
                "user",
                null,
                "Crea otra version basada en esta imagen",
                "auto",
                List.of(cameraPhoto)
        );

        assertEquals("sid-img", response.getSessionId());
        assertEquals("Imagen transformada", response.getReply());
        verify(imageGenerationService).generate(
                "user",
                null,
                "Crea otra version basada en esta imagen",
                "image",
                null,
                List.of(cameraPhoto)
        );
    }
}


