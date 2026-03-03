package com.example.apiasistente.rag.dto;

/**
 * Accion manual sobre un caso del robot RAG.
 */
public class RagMaintenanceCaseDecisionRequest {

    private String action;
    private String notes;
    private String proposedContent;

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getProposedContent() {
        return proposedContent;
    }

    public void setProposedContent(String proposedContent) {
        this.proposedContent = proposedContent;
    }
}
