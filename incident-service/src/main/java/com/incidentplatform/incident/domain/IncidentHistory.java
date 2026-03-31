package com.incidentplatform.incident.domain;

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
        name = "incident_history",
        indexes = {
                @Index(name = "idx_incident_history_incident_id",
                        columnList = "incident_id"),
                @Index(name = "idx_incident_history_tenant_id",
                        columnList = "tenant_id"),
                @Index(name = "idx_incident_history_changed_at",
                        columnList = "changed_at")
        }
)
public class IncidentHistory {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotNull
    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @NotBlank
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private IncidentStatus fromStatus;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private IncidentStatus toStatus;

    @Column(name = "changed_by", updatable = false)
    private UUID changedBy;

    @NotBlank
    @Column(name = "change_source", nullable = false, updatable = false)
    private String changeSource;

    @Column(name = "comment", columnDefinition = "TEXT", updatable = false)
    private String comment;

    @NotNull
    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected IncidentHistory() {}

    public IncidentHistory(UUID incidentId,
                           String tenantId,
                           IncidentStatus fromStatus,
                           IncidentStatus toStatus,
                           UUID changedBy,
                           String changeSource,
                           String comment) {
        this.id = UUID.randomUUID();
        this.incidentId = incidentId;
        this.tenantId = tenantId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedBy = changedBy;
        this.changeSource = changeSource;
        this.comment = comment;
        this.changedAt = Instant.now();
    }

    public static IncidentHistory forCreation(UUID incidentId,
                                              String tenantId,
                                              String changeSource) {
        return new IncidentHistory(
                incidentId, tenantId,
                null,
                IncidentStatus.OPEN,
                null,
                changeSource,
                "Incident created from alert"
        );
    }

    public static IncidentHistory forAutomaticChange(UUID incidentId,
                                                     String tenantId,
                                                     IncidentStatus fromStatus,
                                                     IncidentStatus toStatus,
                                                     String changeSource,
                                                     String comment) {
        return new IncidentHistory(
                incidentId, tenantId,
                fromStatus, toStatus,
                null,
                changeSource, comment
        );
    }

    public UUID getId() { return id; }
    public UUID getIncidentId() { return incidentId; }
    public String getTenantId() { return tenantId; }
    public IncidentStatus getFromStatus() { return fromStatus; }
    public IncidentStatus getToStatus() { return toStatus; }
    public UUID getChangedBy() { return changedBy; }
    public String getChangeSource() { return changeSource; }
    public String getComment() { return comment; }
    public Instant getChangedAt() { return changedAt; }
}