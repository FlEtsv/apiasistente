package com.example.apiasistente.chat.service;

import com.example.apiasistente.chat.config.ChatProcessRouterProperties;
import com.example.apiasistente.chat.dto.ChatMediaInput;
import com.example.apiasistente.shared.ai.OllamaClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatProcessRouterTest {

    @Mock
    private ChatModelSelector modelSelector;

    @Mock
    private OllamaClient ollamaClient;

    private ChatProcessRouterProperties properties;
    private ChatProcessRouter router;

    @BeforeEach
    void setUp() {
        properties = new ChatProcessRouterProperties();
        properties.setLlmAssessmentEnabled(false);
        router = new ChatProcessRouter(modelSelector, ollamaClient, properties);
    }

    @Test
    void routesAutoPromptToImageWhenRequestIsExplicitlyGenerative() {
        ChatProcessRouter.ProcessDecision decision = router.decide(
                "Genera una imagen de un gato astronauta en marte",
                "auto",
                List.of()
        );

        assertEquals(ChatProcessRouter.ProcessRoute.IMAGE, decision.route());
        assertEquals("heuristic", decision.source());
        assertFalse(decision.usedLlm());
        assertEquals(ChatProcessRouter.PipelineHint.IMAGE_TXT2IMG, decision.pipeline());
        assertEquals(ChatModelSelector.IMAGE_ALIAS, decision.recommendedModelAlias());
        verifyNoInteractions(ollamaClient, modelSelector);
    }

    @Test
    void keepsCameraAnalysisInChatFlow() {
        ChatMediaInput cameraPhoto = new ChatMediaInput();
        cameraPhoto.setName("camera.jpg");
        cameraPhoto.setMimeType("image/jpeg");
        cameraPhoto.setBase64("ZmFrZS1iYXNlNjQ=");

        ChatProcessRouter.ProcessDecision decision = router.decide(
                "Analiza esta imagen y dime que ves",
                "auto",
                List.of(cameraPhoto)
        );

        assertEquals(ChatProcessRouter.ProcessRoute.CHAT, decision.route());
        assertFalse(decision.usedLlm());
        assertEquals(ChatProcessRouter.PipelineHint.VISION_ANALYZE, decision.pipeline());
        assertEquals(ChatModelSelector.CHAT_ALIAS, decision.recommendedModelAlias());
        verifyNoInteractions(ollamaClient, modelSelector);
    }

    @Test
    void routesCameraEditIntentToImageGenerationFlow() {
        ChatMediaInput cameraPhoto = new ChatMediaInput();
        cameraPhoto.setName("camera.jpg");
        cameraPhoto.setMimeType("image/jpeg");
        cameraPhoto.setBase64("ZmFrZS1iYXNlNjQ=");

        ChatProcessRouter.ProcessDecision decision = router.decide(
                "Mejora esta foto con estilo cinematic",
                "auto",
                List.of(cameraPhoto)
        );

        assertEquals(ChatProcessRouter.ProcessRoute.IMAGE, decision.route());
        assertFalse(decision.usedLlm());
        assertEquals(ChatProcessRouter.PipelineHint.IMAGE_IMG2IMG, decision.pipeline());
        assertEquals(ChatModelSelector.IMAGE_ALIAS, decision.recommendedModelAlias());
        verifyNoInteractions(ollamaClient, modelSelector);
    }

    @Test
    void routesImageTableExtractionIntentToChatFlow() {
        ChatMediaInput cameraPhoto = new ChatMediaInput();
        cameraPhoto.setName("camera.jpg");
        cameraPhoto.setMimeType("image/jpeg");
        cameraPhoto.setBase64("ZmFrZS1iYXNlNjQ=");

        ChatProcessRouter.ProcessDecision decision = router.decide(
                "Dame estos datos en una tabla",
                "auto",
                List.of(cameraPhoto)
        );

        assertEquals(ChatProcessRouter.ProcessRoute.CHAT, decision.route());
        assertFalse(decision.usedLlm());
        assertEquals(ChatProcessRouter.PipelineHint.VISION_EXTRACT, decision.pipeline());
        assertEquals(ChatModelSelector.CHAT_ALIAS, decision.recommendedModelAlias());
        verifyNoInteractions(ollamaClient, modelSelector);
    }

    @Test
    void routesSimpleTextToFastChatPipeline() {
        ChatProcessRouter.ProcessDecision decision = router.decide(
                "Hola, que tal",
                "auto",
                List.of()
        );

        assertEquals(ChatProcessRouter.ProcessRoute.CHAT, decision.route());
        assertEquals(ChatProcessRouter.PipelineHint.CHAT_FAST, decision.pipeline());
        assertEquals(ChatModelSelector.FAST_ALIAS, decision.recommendedModelAlias());
    }

    @Test
    void routesComplexTextToComplexChatPipeline() {
        ChatProcessRouter.ProcessDecision decision = router.decide(
                "Compara estrategias de migracion paso a paso con riesgos y trade-offs",
                "auto",
                List.of()
        );

        assertEquals(ChatProcessRouter.ProcessRoute.CHAT, decision.route());
        assertEquals(ChatProcessRouter.PipelineHint.CHAT_RAG, decision.pipeline());
        assertEquals(ChatModelSelector.CHAT_ALIAS, decision.recommendedModelAlias());
    }

    @Test
    void routesRepositorySpecificQuestionToRagPipeline() {
        ChatProcessRouter.ProcessDecision decision = router.decide(
                "Cual es el endpoint de esta API para crear una api key?",
                "auto",
                List.of()
        );

        assertEquals(ChatProcessRouter.ProcessRoute.CHAT, decision.route());
        assertEquals(ChatProcessRouter.PipelineHint.CHAT_RAG, decision.pipeline());
        assertEquals(ChatModelSelector.CHAT_ALIAS, decision.recommendedModelAlias());
    }

    @Test
    void routesTextRenderingToComplexChatPipeline() {
        ChatProcessRouter.ProcessDecision decision = router.decide(
                "Dibuja un girasol con caracteres ascii",
                "auto",
                List.of()
        );

        assertEquals(ChatProcessRouter.ProcessRoute.CHAT, decision.route());
        assertEquals(ChatProcessRouter.PipelineHint.CHAT_COMPLEX, decision.pipeline());
        assertEquals(ChatModelSelector.CHAT_ALIAS, decision.recommendedModelAlias());
    }

    @Test
    void usesFastLlmWhenHeuristicIsAmbiguous() {
        properties.setLlmAssessmentEnabled(true);
        properties.setHeuristicImageThreshold(0.95);
        properties.setLlmConfidenceThreshold(0.60);
        properties.setMinPromptCharsForLlm(6);
        router = new ChatProcessRouter(modelSelector, ollamaClient, properties);

        when(modelSelector.resolveChatModel(ChatModelSelector.FAST_ALIAS)).thenReturn("qwen2.5:7b");
        when(ollamaClient.chat(anyList(), anyString())).thenReturn("""
                {"route":"image","confidence":0.91,"reason":"peticion visual explicita"}
                """);

        ChatProcessRouter.ProcessDecision decision = router.decide(
                "Haz algo visual estilo cinematic 4k",
                "auto",
                List.of()
        );

        assertEquals(ChatProcessRouter.ProcessRoute.IMAGE, decision.route());
        assertTrue(decision.usedLlm());
        assertEquals("llm-fast", decision.source());
        assertEquals(ChatProcessRouter.PipelineHint.IMAGE_TXT2IMG, decision.pipeline());
        assertEquals(ChatModelSelector.IMAGE_ALIAS, decision.recommendedModelAlias());
    }
}
