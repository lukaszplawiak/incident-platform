package com.incidentplatform.shared.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.incidentplatform.shared.dto.AuditEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Publishes audit events to Kafka for compliance and observability.
 *
 * <p>Delegates the actual Kafka send to {@link AuditEventKafkaSender} which
 * carries the {@code @Retryable} annotation. This separation is necessary
 * because Spring AOP proxies only intercept cross-bean method calls —
 * a {@code @Retryable} annotation on a {@code private} method called via
 * {@code this} is silently ignored by the proxy.
 *
 * <h2>Method naming convention</h2>
 * <ul>
 *   <li>{@link #publishIncident} — system-initiated incident event</li>
 *   <li>{@link #publishIncidentUser} — user-initiated incident event</li>
 *   <li>{@link #publishAuth} — user-initiated auth event (login, logout, etc.)</li>
 *   <li>{@link #publishAuthSystem} — system-initiated auth event (account lock, etc.)</li>
 * </ul>
 */
@Component
public class AuditEventPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(AuditEventPublisher.class);

    private final AuditEventKafkaSender sender;

    public AuditEventPublisher(AuditEventKafkaSender sender) {
        this.sender = sender;
    }

    // ── Incident events ───────────────────────────────────────────────────

    /**
     * Publishes a system-initiated incident audit event.
     *
     * @param incidentId    the incident UUID
     * @param tenantId      the tenant that owns the incident
     * @param eventType     one of {@link AuditEventTypes} constants
     * @param sourceService the service publishing the event (e.g. "incident-service")
     * @param detail        human-readable description of the event
     * @param metadata      additional structured context
     */
    public void publishIncident(UUID incidentId,
                                String tenantId,
                                String eventType,
                                String sourceService,
                                String detail,
                                Map<String, Object> metadata) {
        publish(AuditEventMessage.incident(
                incidentId, tenantId, eventType,
                sourceService, detail, metadata));
    }

    /**
     * Publishes a user-initiated incident audit event.
     *
     * @param incidentId    the incident UUID
     * @param tenantId      the tenant that owns the incident
     * @param eventType     one of {@link AuditEventTypes} constants
     * @param sourceService the service publishing the event
     * @param userId        the UUID of the user who performed the action
     * @param detail        human-readable description of the event
     * @param metadata      additional structured context
     */
    public void publishIncidentUser(UUID incidentId,
                                    String tenantId,
                                    String eventType,
                                    String sourceService,
                                    String userId,
                                    String detail,
                                    Map<String, Object> metadata) {
        publish(AuditEventMessage.incidentUser(
                incidentId, tenantId, eventType,
                sourceService, userId, detail, metadata));
    }

    // ── Auth events ───────────────────────────────────────────────────────

    /**
     * Publishes a user-initiated auth audit event.
     *
     * <p>Used for self-service actions (login, logout, password change)
     * and admin actions (create user, delete user, update roles) where
     * {@code actor} may differ from {@code userId}.
     *
     * @param userId        the UUID of the affected user
     * @param tenantId      the tenant
     * @param eventType     one of {@link AuditEventTypes} AUTH_* constants
     * @param sourceService the service publishing the event (e.g. "auth-service")
     * @param actor         UUID of the user who performed the action
     * @param detail        human-readable description of the event
     * @param metadata      additional structured context
     */
    public void publishAuth(UUID userId,
                            String tenantId,
                            String eventType,
                            String sourceService,
                            String actor,
                            String detail,
                            Map<String, Object> metadata) {
        publish(AuditEventMessage.auth(
                userId, tenantId, eventType,
                sourceService, actor, detail, metadata));
    }

    /**
     * Publishes a system-initiated auth audit event.
     *
     * <p>Used for system actions like account locking by rate limiter.
     *
     * @param userId        the UUID of the affected user
     * @param tenantId      the tenant
     * @param eventType     one of {@link AuditEventTypes} AUTH_* constants
     * @param sourceService the service publishing the event
     * @param detail        human-readable description of the event
     * @param metadata      additional structured context
     */
    public void publishAuthSystem(UUID userId,
                                  String tenantId,
                                  String eventType,
                                  String sourceService,
                                  String detail,
                                  Map<String, Object> metadata) {
        publish(AuditEventMessage.authSystem(
                userId, tenantId, eventType,
                sourceService, detail, metadata));
    }

    // ── internal ──────────────────────────────────────────────────────────

    private void publish(AuditEventMessage message) {
        try {
            sender.send(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize audit event — not retrying: " +
                            "eventType={}, resourceId={}",
                    message.eventType(), message.resourceId(), e);
        } catch (Exception e) {
            log.error("Failed to publish audit event after all retries: " +
                            "eventType={}, resourceId={}",
                    message.eventType(), message.resourceId(), e);
        }
    }
}