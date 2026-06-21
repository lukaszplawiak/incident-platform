package com.incidentplatform.incident.domain;

import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.SourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "incidents",
        indexes = {
                @Index(name = "idx_incidents_tenant_status",
                        columnList = "tenant_id, status"),
                @Index(name = "idx_incidents_tenant_fingerprint",
                        columnList = "tenant_id, alert_fingerprint"),
                @Index(name = "idx_incidents_fingerprint",
                        columnList = "alert_fingerprint"),
                @Index(name = "idx_incidents_created_at",
                        columnList = "created_at")
        }
)
public class Incident {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotBlank
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private IncidentStatus status;

    @NotBlank
    @Size(max = 255)
    @Column(name = "title", nullable = false)
    private String title;

    @Size(max = 5000)
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private Severity severity;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;

    @NotBlank
    @Column(name = "source", nullable = false)
    private String source;

    @NotBlank
    @Column(name = "alert_fingerprint", nullable = false)
    private String alertFingerprint;

    @Column(name = "alert_id")
    private UUID alertId;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "alert_fired_at")
    private Instant alertFiredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "escalation_level", nullable = false)
    private int escalationLevel = 0;

    protected Incident() {}

    public Incident(String tenantId,
                    String title,
                    String description,
                    Severity severity,
                    SourceType sourceType,
                    String source,
                    String alertFingerprint,
                    UUID alertId,
                    Instant alertFiredAt) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.status = IncidentStatus.OPEN;
        this.title = title;
        this.description = description;
        this.severity = severity;
        this.sourceType = sourceType;
        this.source = source;
        this.alertFingerprint = alertFingerprint;
        this.alertId = alertId;
        this.alertFiredAt = alertFiredAt;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Acknowledges the incident: transitions to {@link IncidentStatus#ACKNOWLEDGED}
     * and, if nobody is assigned yet, assigns the acknowledging user.
     *
     * <p>The auto-assign rule lives here rather than in the calling service so
     * it cannot be bypassed by a future caller that forgets to apply it —
     * acknowledging an incident always atomically claims it if unclaimed.
     */
    public void acknowledge(UUID acknowledgedBy) {
        IncidentFsm.validateTransition(this.status, IncidentStatus.ACKNOWLEDGED);
        this.status = IncidentStatus.ACKNOWLEDGED;
        this.acknowledgedAt = Instant.now();
        this.updatedAt = Instant.now();

        if (this.assignedTo == null) {
            this.assignedTo = acknowledgedBy;
        }
    }

    public void resolve() {
        IncidentFsm.validateTransition(this.status, IncidentStatus.RESOLVED);
        this.status = IncidentStatus.RESOLVED;
        this.resolvedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void close() {
        IncidentFsm.validateTransition(this.status, IncidentStatus.CLOSED);
        this.status = IncidentStatus.CLOSED;
        this.closedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void assignTo(UUID userId) {
        this.assignedTo = userId;
        this.updatedAt = Instant.now();
    }

    /**
     * Records the current escalation level as reported by escalation-service's
     * IncidentEscalatedEvent. This is an independent attribute, not a status
     * transition — it does not go through IncidentFsm and does not affect
     * {@link #getStatus()}. An incident can be ACKNOWLEDGED and escalated to
     * level 2 at the same time.
     *
     * <p>Called by IncidentEscalationEventConsumer when incident-service
     * consumes its own published event back from incidents.lifecycle —
     * closing the loop so the locally stored escalation level always reflects
     * what escalation-service's EscalationScheduler has actually done,
     * including automatic (timeout-driven) escalations that never go through
     * the REST API.
     */
    public void recordEscalation(int escalationLevel) {
        this.escalationLevel = escalationLevel;
        this.updatedAt = Instant.now();
    }

    public void updateSeverity(Severity newSeverity) {
        this.severity = newSeverity;
        this.updatedAt = Instant.now();
    }

    public Long getMttaMinutes() {
        return minutesBetween(createdAt, acknowledgedAt);
    }

    public Long getMttrMinutes() {
        return minutesBetween(createdAt, resolvedAt);
    }

    /**
     * Minutes between {@code start} and {@code end}, or {@code null} if
     * {@code end} hasn't happened yet (incident not yet acknowledged/resolved).
     * Uses {@link Duration} rather than raw epoch-millisecond arithmetic —
     * the standard Java time API for this kind of calculation, and shared
     * here so MTTA/MTTR don't each repeat the same null-check-and-subtract
     * pattern.
     */
    private static Long minutesBetween(Instant start, Instant end) {
        if (end == null) {
            return null;
        }
        return Duration.between(start, end).toMinutes();
    }

    public boolean isActive() {
        return !IncidentFsm.isTerminalState(this.status);
    }

    public UUID getId()                    { return id; }
    public String getTenantId()            { return tenantId; }
    public IncidentStatus getStatus()      { return status; }
    public String getTitle()               { return title; }
    public String getDescription()         { return description; }
    public Severity getSeverity()          { return severity; }
    public SourceType getSourceType()      { return sourceType; }
    public String getSource()              { return source; }
    public String getAlertFingerprint()    { return alertFingerprint; }
    public UUID getAlertId()               { return alertId; }
    public UUID getAssignedTo()            { return assignedTo; }
    public Instant getAcknowledgedAt()     { return acknowledgedAt; }
    public Instant getResolvedAt()         { return resolvedAt; }
    public Instant getClosedAt()           { return closedAt; }
    public Instant getAlertFiredAt()       { return alertFiredAt; }
    public Instant getCreatedAt()          { return createdAt; }
    public Instant getUpdatedAt()          { return updatedAt; }
    public Long getVersion()               { return version; }
    public int getEscalationLevel()        { return escalationLevel; }
}