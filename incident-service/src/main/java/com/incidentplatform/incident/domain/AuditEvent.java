package com.incidentplatform.incident.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(
        name = "audit_events",
        indexes = {
                @Index(name = "idx_audit_events_incident",
                        columnList = "tenant_id, incident_id, occurred_at DESC"),
                @Index(name = "idx_audit_events_tenant",
                        columnList = "tenant_id, occurred_at DESC"),
                @Index(name = "idx_audit_events_type",
                        columnList = "tenant_id, event_type, occurred_at DESC")
        }
)
public class AuditEvent {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotNull
    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @NotBlank
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @NotBlank
    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "actor")
    private String actor;

    @NotBlank
    @Column(name = "actor_type", nullable = false)
    private String actorType;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @NotBlank
    @Column(name = "source_service", nullable = false)
    private String sourceService;

    @NotNull
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditEvent() {}

    public static AuditEvent system(UUID incidentId,
                                    String tenantId,
                                    String eventType,
                                    String sourceService,
                                    String detail,
                                    Map<String, Object> metadata) {
        return create(incidentId, tenantId, eventType,
                sourceService, sourceService, "SYSTEM",
                detail, metadata);
    }

    public static AuditEvent user(UUID incidentId,
                                  String tenantId,
                                  String eventType,
                                  String sourceService,
                                  String userId,
                                  String detail,
                                  Map<String, Object> metadata) {
        return create(incidentId, tenantId, eventType,
                sourceService, userId, "USER",
                detail, metadata);
    }

    private static AuditEvent create(UUID incidentId,
                                     String tenantId,
                                     String eventType,
                                     String sourceService,
                                     String actor,
                                     String actorType,
                                     String detail,
                                     Map<String, Object> metadata) {
        final AuditEvent event = new AuditEvent();
        event.id = UUID.randomUUID();
        event.incidentId = incidentId;
        event.tenantId = tenantId;
        event.eventType = eventType;
        event.sourceService = sourceService;
        event.actor = actor;
        event.actorType = actorType;
        event.detail = detail;
        event.metadata = metadata;
        event.occurredAt = Instant.now();
        event.createdAt = Instant.now();
        return event;
    }

    public UUID getId()             { return id; }
    public UUID getIncidentId()     { return incidentId; }
    public String getTenantId()     { return tenantId; }
    public String getEventType()    { return eventType; }
    public String getActor()        { return actor; }
    public String getActorType()    { return actorType; }
    public String getDetail()       { return detail; }
    public Map<String, Object> getMetadata() { return metadata; }
    public String getSourceService(){ return sourceService; }
    public Instant getOccurredAt()  { return occurredAt; }
    public Instant getCreatedAt()   { return createdAt; }
}