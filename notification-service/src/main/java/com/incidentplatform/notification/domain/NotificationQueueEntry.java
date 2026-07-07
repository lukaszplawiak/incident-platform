package com.incidentplatform.notification.domain;

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

/**
 * Outbox entry written by the Kafka consumer and processed by
 * {@code NotificationScheduler}.
 *
 * <h2>Why this exists — Outbox Pattern</h2>
 * Previously {@code IncidentEventConsumer} called
 * {@code NotificationService.processEvent()} synchronously, which made
 * HTTP calls to the oncall-service and Slack/email/SMS APIs on the Kafka
 * consumer thread — blocking it for seconds per event.
 *
 * <p>Now the consumer only writes a {@code PENDING} entry here and
 * acknowledges immediately (~10ms total). The scheduler picks up
 * {@code PENDING} entries, resolves the current oncall recipient, and
 * sends the actual notifications in a dedicated scheduled thread.
 *
 * <h2>Recipient resolved at send time, not enqueue time</h2>
 * This entry stores {@code eventType}, {@code severity}, and {@code title}
 * but NOT the recipient. The recipient (oncall person) is resolved by the
 * scheduler at the moment of sending — so if the oncall schedule rotates
 * in the 30 seconds between enqueue and processing, the notification goes
 * to whoever is currently on duty, not the person who was on duty when the
 * Kafka event arrived.
 *
 * <h2>Relationship to notification_log</h2>
 * {@link NotificationLog} is an immutable append-only audit trail written
 * after each channel send (SENT or FAILED). This table is a mutable work
 * queue — the two have separate lifecycles and separate concerns.
 */
@Entity
@Table(
        name = "notification_queue",
        indexes = {
                @Index(name = "idx_notification_queue_status_created",
                        columnList = "status, created_at")
        }
)
public class NotificationQueueEntry {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @NotNull
    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @NotBlank
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @NotBlank
    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, updatable = false, length = 20)
    private Severity severity;

    @NotBlank
    @Column(name = "title", nullable = false, updatable = false, length = 500)
    private String title;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private NotificationQueueStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected NotificationQueueEntry() {}

    /**
     * Creates a new PENDING outbox entry.
     * Called by the Kafka consumer immediately after receiving an event.
     */
    public static NotificationQueueEntry pending(UUID incidentId,
                                                 String tenantId,
                                                 String eventType,
                                                 Severity severity,
                                                 String title) {
        final NotificationQueueEntry entry = new NotificationQueueEntry();
        entry.id = UUID.randomUUID();
        entry.incidentId = incidentId;
        entry.tenantId = tenantId;
        entry.eventType = eventType;
        entry.severity = severity;
        entry.title = title;
        entry.status = NotificationQueueStatus.PENDING;
        entry.createdAt = Instant.now();
        return entry;
    }

    /**
     * Marks this entry as successfully processed — all channels sent.
     */
    public void markSent() {
        this.status = NotificationQueueStatus.SENT;
        this.processedAt = Instant.now();
    }

    /**
     * Marks this entry as failed — processing could not complete.
     */
    public void markFailed(String errorMessage) {
        this.status = NotificationQueueStatus.FAILED;
        this.errorMessage = errorMessage;
        this.processedAt = Instant.now();
    }

    public UUID getId()                          { return id; }
    public UUID getIncidentId()                  { return incidentId; }
    public String getTenantId()                  { return tenantId; }
    public String getEventType()                 { return eventType; }
    public Severity getSeverity()                { return severity; }
    public String getTitle()                     { return title; }
    public NotificationQueueStatus getStatus()   { return status; }
    public String getErrorMessage()              { return errorMessage; }
    public Instant getCreatedAt()                { return createdAt; }
    public Instant getProcessedAt()              { return processedAt; }
}