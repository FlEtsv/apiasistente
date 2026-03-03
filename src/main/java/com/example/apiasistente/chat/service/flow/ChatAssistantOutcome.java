package com.example.apiasistente.chat.service.flow;

/**
 * Resultado final del asistente para un turno de chat.
 */
record ChatAssistantOutcome(String assistantText,
                            ChatGroundingService.GroundingAnswerAssessment answerAssessment) {
}

