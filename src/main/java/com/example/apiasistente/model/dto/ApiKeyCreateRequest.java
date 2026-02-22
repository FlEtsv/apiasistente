package com.example.apiasistente.model.dto;

import jakarta.validation.constraints.NotBlank;

public class ApiKeyCreateRequest {

    @NotBlank
    private String label;

    /**
     * Si es true, la clave puede activar specialMode en /api/ext/chat
     * para aislar conversaciones por externalUserId.
     */
    private boolean specialModeEnabled;

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public boolean isSpecialModeEnabled() { return specialModeEnabled; }
    public void setSpecialModeEnabled(boolean specialModeEnabled) { this.specialModeEnabled = specialModeEnabled; }
}
