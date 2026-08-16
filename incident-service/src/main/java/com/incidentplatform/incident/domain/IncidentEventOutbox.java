package com.incidentplatform.incident.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox entry for {@code incidents.lifecycle} Kafka events (backlog #36).
 *
 * <h2>Outbox Pattern</h2>
 * {@code IncidentEventPublisher} creates a PENDING entry in the same
 * transaction as the {@code Incident}/{@code IncidentHistory} change it
 * describes, and returns immediately — no direct Kafka call from within
 * that transaction. {@code IncidentEventOutboxScheduler} picks up PENDING
 * entries on a dedicated scheduled thread and publishes them to Kafka via
 * {@code IncidentEventKafkaSender}.
 *
 * <p>Because a row only exists here if its transaction actually committed,
 * there is no "phantom event" scenario — an event can never be published
 * for a database change that didn't actually happen. See migration V9's
 * table comment for the full account of the bug this fixes.
 *
 * <h2>payload</h2>
 * The already-serialized JSON body of the {@code IncidentEvent} — built
 * once, at write time, by {@code IncidentEventPublisher} (which already
 * has the fully-populated {@code Incident} entity in hand). The scheduler
 * publishes this JSON string as-is; it never deserializes and re-serializes
 * it, avoiding any risk of the payload subtly changing shape between write
 * and publish if the event's Java record definition evolves in between.
 */
@Entity
@Table(name = "incident_event_outbox")
public class IncidentEventOutbox {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    /**
     * Matches {@code IncidentEventTypes} constants (e.g.
     * {@code INCIDENT_OPENED}) — the same value that goes into the
     * {@code X-Event-Type} Kafka header on publish.
     */
    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @Column(name = "payload", nullable = false, updatable = false,
            columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IncidentEventOutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected IncidentEventOutbox() {}

    /**
     * Creates a PENDING outbox entry. {@code payload} must already be the
     * fully serialized JSON body — see this class's Javadoc for why
     * serialization happens once, at write time, not at publish time.
     */
    public static IncidentEventOutbox pending(UUID incidentId, String tenantId,
                                              String eventType, String payload) {
        final IncidentEventOutbox entry = new IncidentEventOutbox();
        entry.id         = UUID.randomUUID();
        entry.incidentId = incidentId;
        entry.tenantId   = tenantId;
        entry.eventType  = eventType;
        entry.payload    = payload;
        entry.status     = IncidentEventOutboxStatus.PENDING;
        entry.createdAt  = Instant.now();
        return entry;
    }

    /**
     * Marks this entry as successfully published — confirmed by the
     * scheduler blocking on the Kafka broker's acknowledgment
     * ({@code IncidentEventKafkaSender.sendRawSync}), not just handed off
     * to an async send.
     */
    public void markPublished() {
        this.status       = IncidentEventOutboxStatus.PUBLISHED;
        this.publishedAt  = Instant.now();
        this.errorMessage = null;
    }

    /**
     * Records a failed publish attempt. Status deliberately stays PENDING
     * — see {@link IncidentEventOutboxStatus}'s Javadoc for why every
     * failure here is treated as retryable, with no permanent-failure
     * state to fall into.
     */
    public void markFailed(String errorMessage) {
        this.retryCount++;
        this.errorMessage = errorMessage;
    }

    public UUID getId()                            { return id; }
    public UUID getIncidentId()                    { return incidentId; }
    public String getTenantId()                    { return tenantId; }
    public String getEventType()                   { return eventType; }
    public String getPayload()                     { return payload; }
    public IncidentEventOutboxStatus getStatus()   { return status; }
    public int getRetryCount()                     { return retryCount; }
    public String getErrorMessage()                { return errorMessage; }
    public Instant getCreatedAt()                  { return createdAt; }
    public Instant getPublishedAt()                { return publishedAt; }
}