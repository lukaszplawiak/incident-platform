package com.incidentplatform.notification.domain;

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
        name = "notification_log",
        indexes = {
                @Index(name = "idx_notification_log_incident_id",
                        columnList = "incident_id"),
                @Index(name = "idx_notification_log_tenant_id",
                        columnList = "tenant_id"),
                @Index(name = "idx_notification_log_sent_at",
                        columnList = "sent_at")
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

    @NotBlank
    @Column(name = "status", nullable = false, updatable = false)
    private String status;

    @Column(name = "error_message", columnDefinition = "TEXT", updatable = false)
    private String errorMessage;

    @NotNull
    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    protected NotificationLog() {}

    private NotificationLog(UUID incidentId, String tenantId, String eventType,
                            String channel, String recipient, String subject,
                            String message, String status, String errorMessage) {
        this.id = UUID.randomUUID();
        this.incidentId = incidentId;
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.channel = channel;
        this.recipient = recipient;
        this.subject = subject;
        this.message = message;
        this.status = status;
        this.errorMessage = errorMessage;
        this.sentAt = Instant.now();
    }

    public static NotificationLog sent(UUID incidentId, String tenantId,
                                       String eventType, String channel,
                                       String recipient, String subject,
                                       String message) {
        return new NotificationLog(incidentId, tenantId, eventType,
                channel, recipient, subject, message, "SENT", null);
    }

    public static NotificationLog failed(UUID incidentId, String tenantId,
                                         String eventType, String channel,
                                         String recipient, String errorMessage) {
        return new NotificationLog(incidentId, tenantId, eventType,
                channel, recipient, null, null, "FAILED", errorMessage);
    }

    public static NotificationLog skipped(UUID incidentId, String tenantId,
                                          String eventType, String channel,
                                          String recipient, String reason) {
        return new NotificationLog(incidentId, tenantId, eventType,
                channel, recipient, null, null, "SKIPPED", reason);
    }

    public UUID getId() { return id; }
    public UUID getIncidentId() { return incidentId; }
    public String getTenantId() { return tenantId; }
    public String getEventType() { return eventType; }
    public String getChannel() { return channel; }
    public String getRecipient() { return recipient; }
    public String getSubject() { return subject; }
    public String getMessage() { return message; }
    public String getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getSentAt() { return sentAt; }
}