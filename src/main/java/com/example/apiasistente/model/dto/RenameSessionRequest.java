package com.example.apiasistente.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class RenameSessionRequest {
    @NotBlank
    @Size(max = 120)
    private String title;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
}
