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
    void routesAutoPromptToImageGenerateWhenRequestIsExplicitlyGenerative() {
        ChatProcessRouter.ProcessDecision decision = router.decide(
                "Genera una imagen de un gato astronauta en marte",
                "auto",
                List.of()
        );

        assertEquals(ChatProcessRouter.ProcessRoute.IMAGE_GENERATE, decision.route());
        assertEquals("hard-rule", decision.source());
        assertFalse(decision.usedLlm());
        assertEquals(ChatProcessRouter.PipelineHint.IMAGE_TXT2IMG, decision.pipeline());
        assertEquals(ChatModelSelector.IMAGE_ALIAS, decision.recommendedModelAlias());
        assertEquals("image", decision.expectedOutput());
        verifyNoInteractions(ollamaClient, modelSelector);
    }

    @Test
    void keepsCameraAnalysisInChatFlow() {
        ChatMediaInput cameraPhoto = imageInput();

        ChatProcessRouter.ProcessDecision decision = router.decide(
                "Analiza esta imagen y dime que ves",
                "auto",
                List.of(cameraPhoto)
        );

        assertEquals(ChatProcessRouter.ProcessRoute.CHAT, decision.route());
        assertFalse(decision.usedLlm());
        assertEquals(ChatProcessRouter.PipelineHint.VISION_ANALYZE, decision.pipeline());
        assertEquals(ChatModelSelector.VISUAL_ALIAS, decision.recommendedModelAlias());
        verifyNoInteractions(ollamaClient, modelSelector);
    }

    @Test
    void routesCameraEditIntentToImageGenerationFlow() {
        ChatMediaInput cameraPhoto = imageInput();

        ChatProcessRouter.ProcessDecision decision = router.decide(
                "Mejora esta foto con estilo cinematic",
                "auto",
                List.of(cameraPhoto)
        );

        assertEquals(ChatProcessRouter.ProcessRoute.IMAGE_GENERATE, decision.route());
        assertFalse(decision.usedLlm());
        assertEquals(ChatProcessRouter.PipelineHint.IMAGE_IMG2IMG, decision.pipeline());
        assertEquals(ChatModelSelector.IMAGE_ALIAS, decision.recommendedModelAlias());
        verifyNoInteractions(ollamaClient, modelSelector);
    }

    @Test
    void routesImperativeImageEditPromptToImageGeneration() {
        ChatMediaInput cameraPhoto = imageInput();

        ChatProcessRouter.ProcessDecision decision = router.decide(
                "haz mejor la cara y añade otra patita",
                "auto",
                List.of(cameraPhoto)
        );

        assertEquals(ChatProcessRouter.ProcessRoute.IMAGE_GENERATE, decision.route());
        assertEquals("hard-rule", decision.source());
        assertEquals(ChatProcessRouter.PipelineHint.IMAGE_IMG2IMG, decision.pipeline());
        assertEquals(ChatModelSelector.IMAGE_ALIAS, decision.recommendedModelAlias());
        assertEquals("image", decision.expectedOutput());
    }

    @Test
    void routesImageTableExtractionIntentToImageExtractFlow() {
        ChatMediaInput cameraPhoto = imageInput();

        ChatProcessRouter.ProcessDecision decision = router.decide(
                "Dame estos datos en una tabla",
                "auto",
                List.of(cameraPhoto)
        );

        assertEquals(ChatProcessRouter.ProcessRoute.IMAGE_EXTRACT, decision.route());
        assertFalse(decision.usedLlm());
        assertEquals(ChatProcessRouter.PipelineHint.VISION_EXTRACT, decision.pipeline());
        assertEquals(ChatModelSelector.VISUAL_ALIAS, decision.recommendedModelAlias());
        assertEquals("table", decision.expectedOutput());
        verifyNoInteractions(ollamaClient, modelSelector);
    }

    @Test
    void routesActionPromptToAction() {
        ChatProcessRouter.ProcessDecision decision = router.decide(
                "Guarda esto",
                "auto",
                List.of()
        );

        assertEquals(ChatProcessRouter.ProcessRoute.ACTION, decision.route());
        assertTrue(decision.needsAction());
        assertEquals(ChatProcessRouter.PipelineHint.ACTION_EXECUTION, decision.pipeline());
    }

    @Test
    void routesRepositorySpecificQuestionToRagRoute() {
        ChatProcessRouter.ProcessDecision decision = router.decide(
                "Cual es el endpoint de esta API para crear una api key?",
                "auto",
                List.of()
        );

        assertEquals(ChatProcessRouter.ProcessRoute.RAG, decision.route());
        assertTrue(decision.needsRag());
        assertEquals(ChatProcessRouter.PipelineHint.CHAT_RAG, decision.pipeline());
        assertEquals(ChatModelSelector.CHAT_ALIAS, decision.recommendedModelAlias());
    }

    @Test
    void routesMixedImagePipelineWhenPromptRequiresExtractAndThenGenerate() {
        ChatMediaInput cameraPhoto = imageInput();

        ChatProcessRouter.ProcessDecision decision = router.decide(
                "Saca datos de esta imagen y luego genera un grafico",
                "auto",
                List.of(cameraPhoto)
        );

        assertEquals(ChatProcessRouter.ProcessRoute.MIXED_PIPELINE, decision.route());
        assertTrue(decision.needsAction());
        assertEquals(ChatProcessRouter.PipelineHint.MIXED_EXTRACT_THEN_ACTION, decision.pipeline());
    }

    @Test
    void usesFastLlmWhenHeuristicIsAmbiguous() {
        properties.setLlmAssessmentEnabled(true);
        properties.setHeuristicImageThreshold(0.99);
        properties.setLlmConfidenceThreshold(0.60);
        properties.setMinPromptCharsForLlm(6);
        router = new ChatProcessRouter(modelSelector, ollamaClient, properties);

        when(modelSelector.resolveChatModel(ChatModelSelector.FAST_ALIAS)).thenReturn("qwen2.5:7b");
        when(ollamaClient.chat(anyList(), anyString())).thenReturn("""
                {"route":"IMAGE_GENERATE","confidence":0.91,"needs_rag":false,"needs_action":false,"expected_output":"image","reason":"peticion visual explicita"}
                """);

        ChatProcessRouter.ProcessDecision decision = router.decide(
                "algo visual cinematic 4k",
                "auto",
                List.of()
        );

        assertEquals(ChatProcessRouter.ProcessRoute.IMAGE_GENERATE, decision.route());
        assertTrue(decision.usedLlm());
        assertEquals("llm-fast", decision.source());
        assertEquals(ChatProcessRouter.PipelineHint.IMAGE_TXT2IMG, decision.pipeline());
        assertEquals(ChatModelSelector.IMAGE_ALIAS, decision.recommendedModelAlias());
    }

    @Test
    void fallsBackSafelyWhenLlmConfidenceIsLow() {
        properties.setLlmAssessmentEnabled(true);
        properties.setHeuristicImageThreshold(0.99);
        properties.setLlmConfidenceThreshold(0.80);
        properties.setMinPromptCharsForLlm(6);
        router = new ChatProcessRouter(modelSelector, ollamaClient, properties);

        when(modelSelector.resolveChatModel(ChatModelSelector.FAST_ALIAS)).thenReturn("qwen2.5:7b");
        when(ollamaClient.chat(anyList(), anyString())).thenReturn("""
                {"route":"IMAGE_GENERATE","confidence":0.40,"needs_rag":false,"needs_action":false,"expected_output":"image","reason":"dudoso"}
                """);

        ChatProcessRouter.ProcessDecision decision = router.decide(
                "haz algo bonito",
                "auto",
                List.of()
        );

        assertEquals(ChatProcessRouter.ProcessRoute.CHAT, decision.route());
        assertEquals("fallback", decision.source());
        assertFalse(decision.usedLlm());
    }

    private ChatMediaInput imageInput() {
        ChatMediaInput cameraPhoto = new ChatMediaInput();
        cameraPhoto.setName("camera.jpg");
        cameraPhoto.setMimeType("image/jpeg");
        cameraPhoto.setBase64("ZmFrZS1iYXNlNjQ=");
        return cameraPhoto;
    }
}
