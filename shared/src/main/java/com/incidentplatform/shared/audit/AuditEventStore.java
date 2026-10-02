package com.incidentplatform.shared.audit;

import java.util.UUID;

/**
 * Where {@link AuditEventPublisher} writes an audit event when the service has
 * an outbox (backlog #0-84). Implemented by {@link AuditOutbox}.
 *
 * <p>An interface of its own, without JDBC types, because the publisher is a
 * component in every service and {@code spring-jdbc} is optional in
 * {@code shared}: ingestion-service has no database, and its publisher must
 * not depend on a class that references {@code JdbcTemplate} (found in
 * review; it started, but only because nothing resolved the class).
 */
public interface AuditEventStore {

    /** Writes the event, in the caller's transaction if there is one. */
    void enqueue(UUID eventId, String tenantId, String eventType, String payload);
}
