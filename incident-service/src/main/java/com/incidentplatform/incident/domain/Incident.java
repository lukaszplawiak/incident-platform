package com.incidentplatform.incident.domain;

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

    @NotBlank
    @Column(name = "severity", nullable = false)
    private String severity;

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

    protected Incident() {}

    public Incident(String tenantId,
                    String title,
                    String description,
                    String severity,
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

    public void transitionTo(IncidentStatus newStatus) {
        IncidentFsm.validateTransition(this.status, newStatus);
        this.status = newStatus;
        this.updatedAt = Instant.now();

        switch (newStatus) {
            case ACKNOWLEDGED -> this.acknowledgedAt = Instant.now();
            case RESOLVED -> this.resolvedAt = Instant.now();
            case CLOSED -> this.closedAt = Instant.now();
            default -> { /* pozostałe stany nie wymagają timestampa */ }
        }
    }

    public void assignTo(UUID userId) {
        this.assignedTo = userId;
        this.updatedAt = Instant.now();
    }

    public Long getMttaMinutes() {
        if (acknowledgedAt == null) return null;
        return (acknowledgedAt.toEpochMilli() - createdAt.toEpochMilli()) / 60_000;
    }

    public Long getMttrMinutes() {
        if (resolvedAt == null) return null;
        return (resolvedAt.toEpochMilli() - createdAt.toEpochMilli()) / 60_000;
    }

    public boolean isActive() {
        return !IncidentFsm.isTerminalState(this.status);
    }

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public IncidentStatus getStatus() { return status; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getSeverity() { return severity; }
    public SourceType getSourceType() { return sourceType; }
    public String getSource() { return source; }
    public String getAlertFingerprint() { return alertFingerprint; }
    public UUID getAlertId() { return alertId; }
    public UUID getAssignedTo() { return assignedTo; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getClosedAt() { return closedAt; }
    public Instant getAlertFiredAt() { return alertFiredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}