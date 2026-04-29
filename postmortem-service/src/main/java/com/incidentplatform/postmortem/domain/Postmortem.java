package com.incidentplatform.postmortem.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "postmortems",
        indexes = {
                @Index(name = "idx_postmortems_tenant_id",    columnList = "tenant_id"),
                @Index(name = "idx_postmortems_incident_id",  columnList = "incident_id"),
                @Index(name = "idx_postmortems_created_at",   columnList = "created_at"),
                @Index(name = "idx_postmortems_status_retry", columnList = "status, retry_count")
        }
)
public class Postmortem {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotNull
    @Column(name = "incident_id", nullable = false, updatable = false, unique = true)
    private UUID incidentId;

    @NotBlank
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @NotBlank
    @Column(name = "incident_title", nullable = false, updatable = false)
    private String incidentTitle;

    @NotBlank
    @Column(name = "incident_severity", nullable = false, updatable = false)
    private String incidentSeverity;

    @NotNull
    @Column(name = "incident_opened_at", nullable = false, updatable = false)
    private Instant incidentOpenedAt;

    @NotNull
    @Column(name = "incident_resolved_at", nullable = false, updatable = false)
    private Instant incidentResolvedAt;

    @NotNull
    @Column(name = "duration_minutes", nullable = false, updatable = false)
    private Integer durationMinutes;

    @NotBlank
    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "prompt_used", columnDefinition = "TEXT")
    private String promptUsed;

    @NotNull
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Postmortem() {}

    public static Postmortem createGenerating(UUID incidentId,
                                              String tenantId,
                                              String incidentTitle,
                                              String incidentSeverity,
                                              Instant incidentOpenedAt,
                                              Instant incidentResolvedAt,
                                              int durationMinutes) {
        final Postmortem p = new Postmortem();
        p.id = UUID.randomUUID();
        p.incidentId = incidentId;
        p.tenantId = tenantId;
        p.incidentTitle = incidentTitle;
        p.incidentSeverity = incidentSeverity;
        p.incidentOpenedAt = incidentOpenedAt;
        p.incidentResolvedAt = incidentResolvedAt;
        p.durationMinutes = durationMinutes;
        p.status = "GENERATING";
        p.retryCount = 0;
        p.createdAt = Instant.now();
        p.updatedAt = Instant.now();
        return p;
    }

    public void markDraft(String content, String promptUsed) {
        this.content = content;
        this.promptUsed = promptUsed;
        this.status = "DRAFT";
        this.updatedAt = Instant.now();
    }

    public void markFailed(String errorMessage) {
        this.errorMessage = errorMessage;
        this.status = "FAILED";
        this.updatedAt = Instant.now();
    }

    public void markPermanentlyFailed(String errorMessage) {
        this.errorMessage = errorMessage;
        this.status = "PERMANENTLY_FAILED";
        this.updatedAt = Instant.now();
    }

    public void incrementRetryCount() {
        this.retryCount++;
        this.updatedAt = Instant.now();
    }

    public void updateContent(String content) {
        this.content = content;
        this.updatedAt = Instant.now();
    }

    public void markReviewed() {
        this.status = "REVIEWED";
        this.updatedAt = Instant.now();
    }

    public boolean isDraft()             { return "DRAFT".equals(this.status); }
    public boolean isFailed()            { return "FAILED".equals(this.status); }
    public boolean isPermanentlyFailed() { return "PERMANENTLY_FAILED".equals(this.status); }

    public UUID getId()                    { return id; }
    public UUID getIncidentId()            { return incidentId; }
    public String getTenantId()            { return tenantId; }
    public String getIncidentTitle()       { return incidentTitle; }
    public String getIncidentSeverity()    { return incidentSeverity; }
    public Instant getIncidentOpenedAt()   { return incidentOpenedAt; }
    public Instant getIncidentResolvedAt() { return incidentResolvedAt; }
    public Integer getDurationMinutes()    { return durationMinutes; }
    public String getStatus()              { return status; }
    public String getContent()             { return content; }
    public String getErrorMessage()        { return errorMessage; }
    public String getPromptUsed()          { return promptUsed; }
    public Integer getRetryCount()         { return retryCount; }
    public Instant getCreatedAt()          { return createdAt; }
    public Instant getUpdatedAt()          { return updatedAt; }
}