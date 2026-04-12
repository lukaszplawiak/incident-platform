package com.incidentplatform.escalation.domain;

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
        name = "escalation_tasks",
        indexes = {
                @Index(name = "idx_escalation_tasks_pending",
                        columnList = "scheduled_escalation_at"),
                @Index(name = "idx_escalation_tasks_tenant_id",
                        columnList = "tenant_id")
        }
)
public class EscalationTask {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotNull
    @Column(name = "incident_id", nullable = false, updatable = false,
            unique = true)
    private UUID incidentId;

    @NotBlank
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @NotNull
    @Column(name = "incident_opened_at", nullable = false, updatable = false)
    private Instant incidentOpenedAt;

    @NotNull
    @Column(name = "scheduled_escalation_at", nullable = false)
    private Instant scheduledEscalationAt;

    @NotBlank
    @Column(name = "status", nullable = false)
    private String status;

    @NotBlank
    @Column(name = "severity", nullable = false, updatable = false)
    private String severity;

    @NotBlank
    @Column(name = "title", nullable = false, updatable = false)
    private String title;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EscalationTask() {}

    public static EscalationTask create(UUID incidentId,
                                        String tenantId,
                                        Instant incidentOpenedAt,
                                        int thresholdMinutes,
                                        String severity,
                                        String title) {
        final EscalationTask task = new EscalationTask();
        task.id = UUID.randomUUID();
        task.incidentId = incidentId;
        task.tenantId = tenantId;
        task.incidentOpenedAt = incidentOpenedAt;
        task.scheduledEscalationAt = incidentOpenedAt
                .plusSeconds(thresholdMinutes * 60L);
        task.status = "PENDING";
        task.severity = severity;
        task.title = title;
        task.createdAt = Instant.now();
        task.updatedAt = Instant.now();
        return task;
    }

    public void markEscalated() {
        this.status = "ESCALATED";
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        this.status = "CANCELLED";
        this.updatedAt = Instant.now();
    }

    public boolean isPending() {
        return "PENDING".equals(this.status);
    }

    public boolean isDueForEscalation() {
        return isPending() && Instant.now().isAfter(scheduledEscalationAt);
    }

    public UUID getId() { return id; }
    public UUID getIncidentId() { return incidentId; }
    public String getTenantId() { return tenantId; }
    public Instant getIncidentOpenedAt() { return incidentOpenedAt; }
    public Instant getScheduledEscalationAt() { return scheduledEscalationAt; }
    public String getStatus() { return status; }
    public String getSeverity() { return severity; }
    public String getTitle() { return title; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}