package com.example.apiasistente.model.dto;

import jakarta.validation.constraints.NotBlank;

public class ApiKeyCreateRequest {

    @NotBlank
    private String label;

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}
