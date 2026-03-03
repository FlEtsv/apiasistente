package com.example.apiasistente.rag.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Cola persistente de decisiones del robot de mantenimiento.
 */
@Entity
@Table(
        name = "rag_maintenance_case",
        indexes = {
                @Index(name = "idx_rag_case_document", columnList = "documentId"),
                @Index(name = "idx_rag_case_status", columnList = "status"),
                @Index(name = "idx_rag_case_due", columnList = "adminDueAt,autoApplyAt")
        }
)
public class RagMaintenanceCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long documentId;

    @Column(nullable = false, length = 120)
    private String owner;

    @Column(nullable = false, length = 200)
    private String documentTitle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RagMaintenanceSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private RagMaintenanceIssueType issueType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RagMaintenanceCaseStatus status = RagMaintenanceCaseStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RagMaintenanceAction recommendedAction = RagMaintenanceAction.KEEP;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private RagMaintenanceAction aiSuggestedAction;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private RagMaintenanceAction finalAction;

    @Column(nullable = false)
    private long usageCount;

    @Column
    private Instant lastUsedAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(nullable = false)
    private Instant adminDueAt;

    @Column
    private Instant aiDecidedAt;

    @Column
    private Instant autoApplyAt;

    @Column
    private Instant resolvedAt;

    @Column(length = 160)
    private String aiModel;

    @Column(length = 120)
    private String resolvedBy;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String summary;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String originalSnippet;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String proposedContent;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String aiReason;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String auditLog;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getDocumentTitle() {
        return documentTitle;
    }

    public void setDocumentTitle(String documentTitle) {
        this.documentTitle = documentTitle;
    }

    public RagMaintenanceSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(RagMaintenanceSeverity severity) {
        this.severity = severity;
    }

    public RagMaintenanceIssueType getIssueType() {
        return issueType;
    }

    public void setIssueType(RagMaintenanceIssueType issueType) {
        this.issueType = issueType;
    }

    public RagMaintenanceCaseStatus getStatus() {
        return status;
    }

    public void setStatus(RagMaintenanceCaseStatus status) {
        this.status = status;
    }

    public RagMaintenanceAction getRecommendedAction() {
        return recommendedAction;
    }

    public void setRecommendedAction(RagMaintenanceAction recommendedAction) {
        this.recommendedAction = recommendedAction;
    }

    public RagMaintenanceAction getAiSuggestedAction() {
        return aiSuggestedAction;
    }

    public void setAiSuggestedAction(RagMaintenanceAction aiSuggestedAction) {
        this.aiSuggestedAction = aiSuggestedAction;
    }

    public RagMaintenanceAction getFinalAction() {
        return finalAction;
    }

    public void setFinalAction(RagMaintenanceAction finalAction) {
        this.finalAction = finalAction;
    }

    public long getUsageCount() {
        return usageCount;
    }

    public void setUsageCount(long usageCount) {
        this.usageCount = usageCount;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getAdminDueAt() {
        return adminDueAt;
    }

    public void setAdminDueAt(Instant adminDueAt) {
        this.adminDueAt = adminDueAt;
    }

    public Instant getAiDecidedAt() {
        return aiDecidedAt;
    }

    public void setAiDecidedAt(Instant aiDecidedAt) {
        this.aiDecidedAt = aiDecidedAt;
    }

    public Instant getAutoApplyAt() {
        return autoApplyAt;
    }

    public void setAutoApplyAt(Instant autoApplyAt) {
        this.autoApplyAt = autoApplyAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getAiModel() {
        return aiModel;
    }

    public void setAiModel(String aiModel) {
        this.aiModel = aiModel;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getOriginalSnippet() {
        return originalSnippet;
    }

    public void setOriginalSnippet(String originalSnippet) {
        this.originalSnippet = originalSnippet;
    }

    public String getProposedContent() {
        return proposedContent;
    }

    public void setProposedContent(String proposedContent) {
        this.proposedContent = proposedContent;
    }

    public String getAiReason() {
        return aiReason;
    }

    public void setAiReason(String aiReason) {
        this.aiReason = aiReason;
    }

    public String getAuditLog() {
        return auditLog;
    }

    public void setAuditLog(String auditLog) {
        this.auditLog = auditLog;
    }
}
