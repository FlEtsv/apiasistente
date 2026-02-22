package com.example.apiasistente.model.dto;

public class RegistrationCodeCreateRequest {

    private String label;
    private Integer ttlMinutes;

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public Integer getTtlMinutes() { return ttlMinutes; }
    public void setTtlMinutes(Integer ttlMinutes) { this.ttlMinutes = ttlMinutes; }
}
