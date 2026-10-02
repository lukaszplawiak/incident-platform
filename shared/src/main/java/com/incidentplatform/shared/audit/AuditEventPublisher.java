package com.incidentplatform.shared.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.incidentplatform.shared.dto.AuditEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes audit events for compliance and observability.
 *
 * <h2>Through the service's outbox (backlog #0-84)</h2>
 * In a service that sets {@code audit.outbox.table}, every event is written to
 * its outbox table ({@link AuditOutbox}), in the caller's transaction when
 * there is one: the event commits with the action or not at all, and
 * {@link AuditOutboxRelay} sends it to Kafka afterwards, waiting for each
 * acknowledgement. A failure to write it is not swallowed: it fails the
 * action, which is the point (no unaudited change). Without the property the
 * event is sent directly and a failure only logged, as before; the services
 * still doing so move to the outbox in #0-84's second step.
 *
 * <p>An event about a refused action whose transaction rolls back (a wrong
 * MFA code) must be published after that transaction ended, or the rollback
 * removes it with everything else: {@code MfaService} does so. (A first
 * version wrote it in a nested {@code REQUIRES_NEW} transaction; found in
 * review to hold two pooled connections per wrong code.)
 *
 * <p>Metadata is stored as given, in plain text, in the outbox and in the
 * audit trail: identifiers and reasons only, never a secret (token, password,
 * key, TOTP secret), and a key named like one is refused. With the outbox an
 * event the trail cannot store (no tenant, no resource, a field over its
 * column, a payload over {@link #MAX_PAYLOAD_BYTES}) fails the action with an
 * {@link IllegalArgumentException}; without it the event is only logged as
 * refused and not sent, as every failure on that path is.
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

    /**
     * The largest payload accepted (backlog #0-84, found in review): well under
     * Kafka's default 1 MB per record, so no stored event is one Kafka refuses
     * and the relay retries for ever.
     */
    static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    private final AuditEventKafkaSender sender;
    private final AuditEventStore outbox;

    public AuditEventPublisher(AuditEventKafkaSender sender, ObjectProvider<AuditEventStore> outbox) {
        this.sender = sender;
        this.outbox = outbox.getIfAvailable();
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
        if (outbox != null) {
            checkStorable(message);
            outbox.enqueue(message.eventId(), message.tenantId(), message.eventType(), serialized(message));
            return;
        }
        try {
            // The old path keeps its contract, only logging (found in review):
            // notification-, escalation- and postmortem-service run it from
            // scheduled jobs and notification flows that never expected an
            // audit call to throw. The check still keeps an unstorable event
            // off Kafka; their move to the outbox (#0-84's second step) makes
            // it fail the action there too.
            checkStorable(message);
            sender.send(message);
        } catch (IllegalArgumentException e) {
            log.error("Audit event refused, not sent: eventType={}, resourceId={}, reason={}",
                    message.eventType(), message.resourceId(), e.getMessage());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize audit event — not retrying: " +
                            "eventType={}, resourceId={}",
                    message.eventType(), message.resourceId(), e);
        } catch (Exception e) {
            log.error("Failed to publish audit event: " +
                            "eventType={}, resourceId={}",
                    message.eventType(), message.resourceId(), e);
        }
    }

    private String serialized(AuditEventMessage message) {
        final String payload;
        try {
            payload = sender.serialize(message);
        } catch (JsonProcessingException e) {
            // A programming error (an unserializable metadata value): fail the
            // action rather than commit it without its audit event.
            throw new IllegalStateException("Audit event " + message.eventType() + " cannot be serialized", e);
        }
        final int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Audit event " + message.eventType() + " is " + bytes
                    + " bytes, more than " + MAX_PAYLOAD_BYTES + " may be: shorten its detail or metadata");
        }
        return payload;
    }

    /**
     * Refuses an event the audit trail cannot store (backlog #0-84, found in
     * review): {@code audit_events} requires the resource, the tenant, the
     * event type and the source service, within their column lengths. Such an
     * event would be sent and then dropped by the consumer, after the outbox
     * had already marked it sent; refused here, the action fails instead
     * (always a programming error: every caller passes them explicitly).
     */
    static void checkStorable(AuditEventMessage message) {
        requireText("tenantId", message.tenantId(), 255);
        requireText("eventType", message.eventType(), 100);
        requireText("sourceService", message.sourceService(), 100);
        if (message.resourceId() == null) {
            throw new IllegalArgumentException("Audit event " + message.eventType() + " has no resource id");
        }
        if (message.actor() != null && message.actor().length() > 255) {
            throw new IllegalArgumentException("Audit event " + message.eventType()
                    + " has an actor longer than 255 characters");
        }
        if (message.metadata() != null) {
            for (final String key : message.metadata().keySet()) {
                if (looksSecret(key)) {
                    throw new IllegalArgumentException("Audit event " + message.eventType()
                            + " has metadata key '" + key + "', which names a secret: audit metadata is "
                            + "stored in plain text; record an identifier instead");
                }
            }
        }
    }

    /**
     * A metadata key that names a secret (found in review: "no secrets in
     * metadata" was a convention only, and the outbox keeps the payload in
     * plain text for its retention). A name check, not a content scan: it
     * catches the mistake of passing the secret itself under its own name.
     */
    static boolean looksSecret(String key) {
        final String k = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return k.contains("password") || k.contains("secret") || k.contains("credential")
                || k.contains("totp") || k.endsWith("token") || k.equals("rawkey") || k.equals("apikey")
                || k.equals("privatekey");
    }

    private static void requireText(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Audit event has no " + field);
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("Audit event " + field + " is longer than " + maxLength
                    + " characters");
        }
    }
}
