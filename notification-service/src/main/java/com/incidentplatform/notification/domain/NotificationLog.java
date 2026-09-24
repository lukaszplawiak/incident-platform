package com.incidentplatform.notification.domain;

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
 * One attempt to send one notification over one channel: the append-only
 * delivery record, and the per-channel idempotency key read before every send.
 *
 * <h2>Fixed (backlog #0-9): the {@code @Index} list is documentation, not schema</h2>
 * The Flyway migrations are the only source of truth for this table's
 * indexes. {@code ddl-auto} is {@code validate}, which does not check indexes,
 * so nothing here is ever created or verified. The list used to name V1's three
 * single-column indexes, which V2 dropped (and V5 then replaced one of V2's
 * composites), so a reader looking here for "what is indexed" got the wrong
 * answer. It now mirrors what V2 and V5 actually create. A schema change to
 * this table's indexes must update this list too: nothing enforces it yet
 * (backlog #0-36).
 */
@Entity
@Table(
        name = "notification_log",
        indexes = {
                // V2: history of one incident, newest first.
                @Index(name = "idx_notification_log_incident_tenant_sent",
                        columnList = "incident_id, tenant_id, sent_at DESC"),
                // V5: the per-channel idempotency check in
                // NotificationService.processEntry (replaced V2's
                // idx_notification_log_incident_type_channel).
                @Index(name = "idx_notification_log_incident_tenant_type_level_channel",
                        columnList = "incident_id, tenant_id, event_type, escalation_level, channel")
        }
)
public class NotificationLog {

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

    /**
     * Escalation level of the event this row belongs to ({@code 0} for
     * events that are not escalations). Part of the per-channel
     * idempotency key — without it a level-2 escalation's Slack/Email/SMS
     * send is treated as already sent because level 1 used the same
     * channel for the same incident and event type.
     */
    @Column(name = "escalation_level", nullable = false, updatable = false)
    private int escalationLevel;

    @NotBlank
    @Column(name = "channel", nullable = false, updatable = false)
    private String channel;

    @NotBlank
    @Column(name = "recipient", nullable = false, updatable = false)
    private String recipient;

    @Column(name = "subject", updatable = false)
    private String subject;

    @Column(name = "message", columnDefinition = "TEXT", updatable = false)
    private String message;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false)
    private NotificationLogStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT", updatable = false)
    private String errorMessage;

    @NotNull
    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    protected NotificationLog() {}

    private NotificationLog(UUID incidentId, String tenantId, String eventType,
                            int escalationLevel, String channel,
                            String recipient, String subject,
                            String message, NotificationLogStatus status,
                            String errorMessage) {
        this.id = UUID.randomUUID();
        this.incidentId = incidentId;
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.escalationLevel = escalationLevel;
        this.channel = channel;
        this.recipient = recipient;
        this.subject = subject;
        this.message = message;
        this.status = status;
        this.errorMessage = errorMessage;
        this.sentAt = Instant.now();
    }

    public static NotificationLog sent(UUID incidentId, String tenantId,
                                       String eventType, int escalationLevel,
                                       String channel, String recipient,
                                       String subject, String message) {
        return new NotificationLog(incidentId, tenantId, eventType,
                escalationLevel, channel, recipient, subject, message,
                NotificationLogStatus.SENT, null);
    }

    public static NotificationLog failed(UUID incidentId, String tenantId,
                                         String eventType, int escalationLevel,
                                         String channel, String recipient,
                                         String errorMessage) {
        return new NotificationLog(incidentId, tenantId, eventType,
                escalationLevel, channel, recipient, null, null,
                NotificationLogStatus.FAILED, errorMessage);
    }

    public static NotificationLog skipped(UUID incidentId, String tenantId,
                                          String eventType, int escalationLevel,
                                          String channel, String recipient,
                                          String reason) {
        return new NotificationLog(incidentId, tenantId, eventType,
                escalationLevel, channel, recipient, null, null,
                NotificationLogStatus.SKIPPED, reason);
    }

    public UUID getId()                      { return id; }
    public UUID getIncidentId()              { return incidentId; }
    public String getTenantId()              { return tenantId; }
    public String getEventType()             { return eventType; }
    public int getEscalationLevel()          { return escalationLevel; }
    public String getChannel()               { return channel; }
    public String getRecipient()             { return recipient; }
    public String getSubject()               { return subject; }
    public String getMessage()               { return message; }
    public NotificationLogStatus getStatus() { return status; }
    public String getErrorMessage()          { return errorMessage; }
    public Instant getSentAt()               { return sentAt; }
}