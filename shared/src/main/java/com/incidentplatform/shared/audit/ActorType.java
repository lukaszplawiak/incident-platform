package com.incidentplatform.shared.audit;

/**
 * Actor type constants used in audit events to distinguish between
 * system-initiated and user-initiated actions.
 *
 * <p>Used as the {@code actorType} field in {@link com.incidentplatform.shared.dto.AuditEventMessage}
 * and persisted in the {@code AuditEvent} entity. Consumed by
 * {@code AuditEventConsumer} to route events to the correct factory method.
 */
public final class ActorType {

    /** Action performed automatically by the platform (no human actor). */
    public static final String SYSTEM = "SYSTEM";

    /** Action performed by an authenticated user. */
    public static final String USER   = "USER";

    private ActorType() {}
}