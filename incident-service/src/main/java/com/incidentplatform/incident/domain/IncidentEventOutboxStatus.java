package com.incidentplatform.incident.domain;

/**
 * Status of an {@link IncidentEventOutbox} entry.
 *
 * <p>Deliberately only two states, unlike auth-service's
 * {@code AuthEmailStatus} (PENDING/SENT/FAILED/PERMANENTLY_FAILED) — see
 * {@code incident_event_outbox}'s table comment (migration V9) for the
 * full reasoning: a Kafka publish failure is virtually always a transient
 * broker-reachability issue, and every consumer of
 * {@code incidents.lifecycle} already handles at-least-once, idempotent
 * processing, so there is no scenario where permanently giving up on
 * delivering a real incident lifecycle event is the right call.
 */
public enum IncidentEventOutboxStatus {
    PENDING,
    PUBLISHED
}