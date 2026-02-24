package com.example.apiasistente.model.dto;

public class RegistrationCodeCreateRequest {

    private String label;
    private Integer ttlMinutes;
    private java.util.List<String> permissions;

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public Integer getTtlMinutes() { return ttlMinutes; }
    public void setTtlMinutes(Integer ttlMinutes) { this.ttlMinutes = ttlMinutes; }

    public java.util.List<String> getPermissions() { return permissions; }
    public void setPermissions(java.util.List<String> permissions) { this.permissions = permissions; }
}
