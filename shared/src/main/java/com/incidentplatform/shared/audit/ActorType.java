package com.incidentplatform.shared.audit;

/**
 * Actor type for audit events — distinguishes between system-initiated
 * and user-initiated actions.
 *
 * <p>Previously a {@code final class} with {@code public static final String}
 * constants. Changed to a proper Java enum so that:
 * <ul>
 *   <li>The compiler enforces exhaustive handling (e.g. in switch expressions)
 *       when new actor types are added in the future.</li>
 *   <li>{@link com.incidentplatform.incident.domain.AuditEvent} can use
 *       {@code @Enumerated(EnumType.STRING)} — consistent with every other
 *       domain value type in this project ({@link com.incidentplatform.shared.domain.Severity},
 *       {@link com.incidentplatform.oncall.domain.OncallRole},
 *       {@link com.incidentplatform.incident.domain.IncidentStatus} etc.).</li>
 *   <li>Comparisons become enum equality ({@code ==}) rather than
 *       {@code String.equals()}, eliminating a class of potential bugs.</li>
 * </ul>
 *
 * <p>Wire format is unchanged — Jackson serialises this enum to its
 * {@link #name()} ({@code "SYSTEM"} / {@code "USER"}), identical to the
 * previous {@code String} constants, so no Kafka schema migration is needed
 * and existing messages in the {@code audit.events} topic remain readable.
 *
 * <p>The {@code actor_type} column in the {@code audit_events} table already
 * stores {@code VARCHAR} values matching these names — no Flyway migration
 * is required.
 */
public enum ActorType {

    /** Action performed automatically by the platform (no human actor). */
    SYSTEM,

    /** Action performed by an authenticated user. */
    USER
}