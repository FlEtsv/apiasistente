package com.example.apiasistente.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuracion del router de proceso del chat (chat vs generacion de imagen).
 */
@Component
@ConfigurationProperties(prefix = "chat.process-router")
public class ChatProcessRouterProperties {

    /**
     * Activa el enrutado automatico de proceso cuando el cliente usa model=auto/default/null.
     */
    private boolean enabled = true;

    /**
     * Permite consultar el modelo rapido para desambiguar prompts dudosos.
     */
    private boolean llmAssessmentEnabled = true;

    /**
     * Umbral de confianza heuristica por debajo del cual se intenta desambiguar con el mini-modelo.
     */
    private double heuristicImageThreshold = 0.86;

    /**
     * Confianza minima exigida al mini-modelo para sobreescribir la decision heuristica.
     */
    private double llmConfidenceThreshold = 0.72;

    /**
     * Longitud minima del prompt para permitir desambiguacion por mini-modelo.
     */
    private int minPromptCharsForLlm = 16;

    /**
     * Longitud maxima del prompt enviada al mini-modelo para routing.
     */
    private int maxPromptChars = 500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isLlmAssessmentEnabled() {
        return llmAssessmentEnabled;
    }

    public void setLlmAssessmentEnabled(boolean llmAssessmentEnabled) {
        this.llmAssessmentEnabled = llmAssessmentEnabled;
    }

    public double getHeuristicImageThreshold() {
        return heuristicImageThreshold;
    }

    public void setHeuristicImageThreshold(double heuristicImageThreshold) {
        this.heuristicImageThreshold = heuristicImageThreshold;
    }

    public double getLlmConfidenceThreshold() {
        return llmConfidenceThreshold;
    }

    public void setLlmConfidenceThreshold(double llmConfidenceThreshold) {
        this.llmConfidenceThreshold = llmConfidenceThreshold;
    }

    public int getMinPromptCharsForLlm() {
        return minPromptCharsForLlm;
    }

    public void setMinPromptCharsForLlm(int minPromptCharsForLlm) {
        this.minPromptCharsForLlm = minPromptCharsForLlm;
    }

    public int getMaxPromptChars() {
        return maxPromptChars;
    }

    public void setMaxPromptChars(int maxPromptChars) {
        this.maxPromptChars = maxPromptChars;
    }
}
