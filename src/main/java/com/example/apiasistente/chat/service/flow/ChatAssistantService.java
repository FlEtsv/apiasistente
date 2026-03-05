package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.prompt.entity.SystemPrompt;
import com.example.apiasistente.chat.service.ChatPromptSignals;
import com.example.apiasistente.shared.ai.OllamaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ejecuta la generacion final del asistente.
 * Su responsabilidad es construir mensajes, elegir modelo, aplicar guardrails y decidir retries/fallbacks.
 */
@Service
public class ChatAssistantService {

    private static final Logger log = LoggerFactory.getLogger(ChatAssistantService.class);

    private final OllamaClient ollama;
    private final ChatPromptBuilder promptBuilder;
    private final ChatMediaService mediaService;
    private final ChatGroundingService groundingService;
    private final ChatHistoryService historyService;

    public ChatAssistantService(OllamaClient ollama,
                                ChatPromptBuilder promptBuilder,
                                ChatMediaService mediaService,
                                ChatGroundingService groundingService,
                                ChatHistoryService historyService) {
        this.ollama = ollama;
        this.promptBuilder = promptBuilder;
        this.mediaService = mediaService;
        this.groundingService = groundingService;
        this.historyService = historyService;
    }

    /**
     * Produce la respuesta final del turno a partir del contexto ya preparado y del resultado de retrieval.
     */
    public ChatAssistantOutcome answer(ChatTurnContext context, ChatRagContext ragContext) {
        // Si no hubo evidencia suficiente, evita llamar al modelo y devuelve el fallback controlado.
        if (ragContext.missingEvidence()) {
            logAnswerTelemetry(false, ragContext);
            return new ChatAssistantOutcome(
                    ragContext.fallbackMessage(),
                    new ChatGroundingService.GroundingAnswerAssessment(false, 0)
            );
        }

        // El modo weak no intenta completar con informacion parcial; pide precision adicional.
        if (ragContext.weakRagRoute()) {
            logAnswerTelemetry(false, ragContext);
            return weakFallback(context, ragContext);
        }

        // Cuando grounding obligatorio ya sabe que el contexto no es seguro, se corta antes de gastar tokens.
        if (ragContext.enforceGrounding() && !ragContext.groundingDecision().safe()) {
            logAnswerTelemetry(false, ragContext);
            return groundingFallback(ragContext);
        }

        // Construye el prompt final y selecciona el modelo segun ruta, complejidad y modo de ejecucion.
        List<OllamaClient.Message> messages = buildMessages(context, ragContext);
        String model = promptBuilder.selectChatModel(
                context.requestedModel(),
                ragContext.hasRagContext(),
                context.complexQuery(),
                context.multiStepQuery(),
                context.intentRoute(),
                context.directExecutionMode()
        );
        logModelSelection(context, ragContext, model);

        // Ejecuta la respuesta principal y arranca con evaluacion optimista cuando no hubo RAG.
        String assistantText = executeAssistant(messages, model, context, ragContext, shouldSkipGuard(context));
        ChatGroundingService.GroundingAnswerAssessment answerAssessment =
                new ChatGroundingService.GroundingAnswerAssessment(true, 0);

        if (ragContext.ragUsed()) {
            // Con RAG se valida que la salida mantenga anclaje y citas suficientes.
            answerAssessment = groundingService.assessAnswerGrounding(
                    assistantText,
                    ragContext.scored(),
                    ragContext.groundingDecision()
            );

            if (groundingService.shouldRetryWithPrimaryModel(model, ragContext.hasRagContext(), answerAssessment)) {
                ChatAssistantOutcome retryOutcome = retryWithPrimaryModel(
                        messages,
                        model,
                        context,
                        ragContext,
                        assistantText,
                        answerAssessment
                );
                assistantText = retryOutcome.assistantText();
                answerAssessment = retryOutcome.answerAssessment();
            }

            // Ultimo guardrail: si la respuesta sigue insegura, se reemplaza por fallback.
            if (ragContext.enforceGrounding() && !answerAssessment.safe()) {
                assistantText = ragContext.fallbackMessage();
            }
        }

        boolean answerHasCitations = ragContext.ragUsed()
                && groundingService.hasValidSourceCitations(assistantText, ragContext.scored());
        logAnswerTelemetry(answerHasCitations, ragContext);

        return new ChatAssistantOutcome(assistantText, answerAssessment);
    }

    /**
     * Devuelve una pregunta breve cuando hubo retrieval debil y hace falta concretar la solicitud.
     */
    private ChatAssistantOutcome weakFallback(ChatTurnContext context, ChatRagContext ragContext) {
        return new ChatAssistantOutcome(
                groundingService.weakFallback(context.userText()),
                new ChatGroundingService.GroundingAnswerAssessment(
                        false,
                        ragContext.groundingDecision().supportingChunks()
                )
        );
    }

    /**
     * Devuelve el fallback duro cuando el turno no puede responder con grounding seguro.
     */
    private ChatAssistantOutcome groundingFallback(ChatRagContext ragContext) {
        return new ChatAssistantOutcome(
                ragContext.fallbackMessage(),
                new ChatGroundingService.GroundingAnswerAssessment(
                        false,
                        ragContext.groundingDecision().supportingChunks()
                )
        );
    }

    /**
     * Reintenta con el modelo principal cuando el modelo elegido produjo una respuesta no anclada.
     */
    private ChatAssistantOutcome retryWithPrimaryModel(List<OllamaClient.Message> messages,
                                                       String currentModel,
                                                       ChatTurnContext context,
                                                       ChatRagContext ragContext,
                                                       String currentAnswer,
                                                       ChatGroundingService.GroundingAnswerAssessment currentAssessment) {
        String retryModel = promptBuilder.resolvePrimaryChatModel();
        if (!hasText(retryModel) || retryModel.equalsIgnoreCase(currentModel)) {
            return new ChatAssistantOutcome(currentAnswer, currentAssessment);
        }

        if (log.isDebugEnabled()) {
            log.debug("Respuesta no anclada con '{}', reintentando con modelo principal '{}'", currentModel, retryModel);
        }

        // El retry reutiliza exactamente el mismo prompt para aislar el efecto del cambio de modelo.
        String retryAnswer = executeAssistant(messages, retryModel, context, ragContext, false);
        ChatGroundingService.GroundingAnswerAssessment retryAssessment =
                groundingService.assessAnswerGrounding(
                        retryAnswer,
                        ragContext.scored(),
                        ragContext.groundingDecision()
                );
        if (retryAssessment.safe()) {
            return new ChatAssistantOutcome(retryAnswer, retryAssessment);
        }
        return new ChatAssistantOutcome(currentAnswer, currentAssessment);
    }

    /**
     * Ejecuta el modelo y aplica el response guard configurado para limpiar salida o reforzar grounding.
     */
    private String executeAssistant(List<OllamaClient.Message> messages,
                                    String model,
                                    ChatTurnContext context,
                                    ChatRagContext ragContext,
                                    boolean skipGuard) {
        String assistantText = ollama.chat(messages, model);
        return groundingService.applyResponseGuard(
                context.userText(),
                assistantText,
                ragContext.scored(),
                ragContext.ragUsed(),
                skipGuard
        );
    }

    /**
     * Ensambla la secuencia final de mensajes que se enviara al modelo.
     */
    private List<OllamaClient.Message> buildMessages(ChatTurnContext context, ChatRagContext ragContext) {
        List<OllamaClient.Message> messages = new ArrayList<>();
        SystemPrompt prompt = context.session().getSystemPrompt();
        messages.add(new OllamaClient.Message("system", prompt.getContent()));
        if (ragContext.ragUsed()) {
            // Inyecta una politica adicional cuando la respuesta debe quedar completamente anclada.
            messages.add(new OllamaClient.Message(
                    "system",
                    groundingService.buildGroundingSystemPrompt(ragContext.fallbackMessage())
            ));
        }

        promptBuilder.appendRecentHistory(
                messages,
                historyService.recentHistoryForPrompt(context.session().getId(), context.userMsg().getId())
        );

        // El puente visual resume imagenes/documentos para no mezclar esa logica dentro del prompt principal.
        String visualBridge = mediaService.buildVisualBridgeContext(
                context.userText(),
                context.preparedMedia(),
                context.requestedModel()
        );
        // El bloque de usuario cambia segun si el turno va por chat libre o por respuesta anclada con citas.
        String userPrompt = ragContext.ragUsed()
                ? promptBuilder.buildRagBlock(
                context.userText(),
                ragContext.scored(),
                visualBridge,
                context.preparedMedia(),
                ragContext.fallbackMessage()
        )
                : promptBuilder.buildChatBlock(
                context.userText(),
                visualBridge,
                context.preparedMedia(),
                context.taskCompletionMode(),
                context.textRenderMode()
        );
        messages.add(new OllamaClient.Message("user", userPrompt));
        return messages;
    }

    /**
     * Omite el response guard en turnos donde suele degradar la salida mas de lo que ayuda.
     */
    private boolean shouldSkipGuard(ChatTurnContext context) {
        return context.intentRoute() == ChatPromptSignals.IntentRoute.SMALL_TALK || context.textRenderMode();
    }

    /**
     * Registra por que modelo termino usando el turno.
     */
    private void logModelSelection(ChatTurnContext context, ChatRagContext ragContext, String model) {
        String primary = promptBuilder.resolvePrimaryChatModel();
        String modelTier = hasText(primary) && primary.equalsIgnoreCase(model) ? "complex" : "fast-or-custom";
        boolean hasImageMedia = context.preparedMedia().stream().anyMatch(item -> hasText(item.imageBase64()));
        boolean hasDocumentMedia = context.preparedMedia().stream().anyMatch(item -> hasText(item.documentText()));

        log.info(
                "chat_model_route selected={} tier={} requested={} route={} ragUsed={} ragContext={} directExecution={} textRender={} complex={} multiStep={} hasImageMedia={} hasDocumentMedia={}",
                model,
                modelTier,
                context.requestedModel(),
                context.intentRoute(),
                ragContext.ragUsed(),
                ragContext.hasRagContext(),
                context.directExecutionMode(),
                context.textRenderMode(),
                context.complexQuery(),
                context.multiStepQuery(),
                hasImageMedia,
                hasDocumentMedia
        );
    }

    /**
     * Emite telemetria de salida para correlacionar calidad de respuesta y uso real de RAG.
     */
    private void logAnswerTelemetry(boolean answerHasCitations, ChatRagContext ragContext) {
        log.info(
                "rag_answer answer_has_citations={} rag_used={} chunks_used_ids={} source_docs={} context_tokens={} route={}",
                answerHasCitations,
                ragContext.ragUsed(),
                ragContext.retrievalStats().chunksUsedIds(),
                ragContext.retrievalStats().sourceDocs(),
                ragContext.retrievalStats().contextTokens(),
                ragContext.ragRoute()
        );
        if (log.isDebugEnabled()) {
            log.debug(
                    "RAG answer telemetry citations={} ragUsed={} maxSimilarity={} avgSimilarity={}",
                    answerHasCitations,
                    ragContext.ragUsed(),
                    String.format(Locale.US, "%.3f", ragContext.retrievalStats().maxSimilarity()),
                    String.format(Locale.US, "%.3f", ragContext.retrievalStats().avgSimilarity())
            );
        }
    }

    /**
     * Ayuda local para validar texto util.
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}


