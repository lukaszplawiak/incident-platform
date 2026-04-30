package com.incidentplatform.escalation.domain;

import com.incidentplatform.shared.domain.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    public static final int TIMEOUT_CRITICAL = 5;
    public static final int TIMEOUT_HIGH     = 15;
    public static final int TIMEOUT_MEDIUM   = 30;
    public static final int TIMEOUT_LOW      = 60;

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotNull
    @Column(name = "incident_id", nullable = false, updatable = false)
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

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, updatable = false)
    private Severity severity;

    @NotBlank
    @Column(name = "title", nullable = false, updatable = false)
    private String title;

    @NotNull
    @Column(name = "escalation_level", nullable = false)
    private int escalationLevel;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EscalationTask() {}

    public static EscalationTask createLevel1(UUID incidentId,
                                              String tenantId,
                                              Instant incidentOpenedAt,
                                              Severity severity,
                                              String title) {
        final int timeoutMinutes = resolveTimeout(severity);
        return create(incidentId, tenantId, incidentOpenedAt,
                severity, title, 1, timeoutMinutes);
    }

    public static EscalationTask createLevel2(UUID incidentId,
                                              String tenantId,
                                              Instant level1EscalatedAt,
                                              Severity severity,
                                              String title) {
        final int timeoutMinutes = resolveTimeout(severity);
        return create(incidentId, tenantId, level1EscalatedAt,
                severity, title, 2, timeoutMinutes);
    }

    private static EscalationTask create(UUID incidentId,
                                         String tenantId,
                                         Instant startAt,
                                         Severity severity,
                                         String title,
                                         int escalationLevel,
                                         int timeoutMinutes) {
        final EscalationTask task = new EscalationTask();
        task.id = UUID.randomUUID();
        task.incidentId = incidentId;
        task.tenantId = tenantId;
        task.incidentOpenedAt = startAt;
        task.scheduledEscalationAt = startAt.plusSeconds(timeoutMinutes * 60L);
        task.status = "PENDING";
        task.severity = severity;
        task.title = title;
        task.escalationLevel = escalationLevel;
        task.createdAt = Instant.now();
        task.updatedAt = Instant.now();
        return task;
    }

    public static int resolveTimeout(Severity severity) {
        return switch (severity) {
            case CRITICAL -> TIMEOUT_CRITICAL;
            case HIGH     -> TIMEOUT_HIGH;
            case MEDIUM   -> TIMEOUT_MEDIUM;
            case LOW      -> TIMEOUT_LOW;
        };
    }

    public void markEscalated() {
        this.status = "ESCALATED";
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        this.status = "CANCELLED";
        this.updatedAt = Instant.now();
    }

    public boolean isPending()          { return "PENDING".equals(this.status); }
    public boolean isMaxLevel()         { return this.escalationLevel >= 2; }
    public boolean isDueForEscalation() {
        return isPending() && Instant.now().isAfter(scheduledEscalationAt);
    }

    public UUID getId()                       { return id; }
    public UUID getIncidentId()               { return incidentId; }
    public String getTenantId()               { return tenantId; }
    public Instant getIncidentOpenedAt()      { return incidentOpenedAt; }
    public Instant getScheduledEscalationAt() { return scheduledEscalationAt; }
    public String getStatus()                 { return status; }
    public Severity getSeverity()             { return severity; }
    public String getTitle()                  { return title; }
    public int getEscalationLevel()           { return escalationLevel; }
    public Instant getCreatedAt()             { return createdAt; }
    public Instant getUpdatedAt()             { return updatedAt; }
}