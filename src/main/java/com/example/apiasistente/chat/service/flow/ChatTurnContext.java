package com.example.apiasistente.chat.service.flow;

import com.example.apiasistente.chat.entity.ChatMessage;
import com.example.apiasistente.chat.entity.ChatSession;
import com.example.apiasistente.chat.service.ChatPromptSignals;
import com.example.apiasistente.chat.service.ChatTurnPlanner;

import java.util.List;

/**
 * Contexto completo de un turno de chat.
 */
record ChatTurnContext(String username,
                       String userText,
                       String requestedModel,
                       String normalizedExternalUserId,
                       ChatSession session,
                       ChatMessage userMsg,
                       List<ChatMediaService.PreparedMedia> preparedMedia,
                       ChatTurnPlanner.TurnPlan turnPlan,
                       ChatPromptSignals.IntentRoute intentRoute,
                       boolean ragNeeded,
                       boolean complexQuery,
                       boolean multiStepQuery,
                       boolean textRenderMode,
                       boolean directExecutionMode,
                       boolean taskCompletionMode) {
}


