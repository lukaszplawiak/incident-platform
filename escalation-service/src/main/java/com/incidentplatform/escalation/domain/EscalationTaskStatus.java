package com.incidentplatform.escalation.domain;

/**
 * Lifecycle status of an {@link EscalationTask}.
 *
 * <p>Used as {@code @Enumerated(EnumType.STRING)} so the database stores
 * {@code "PENDING"}, {@code "ESCALATED"}, {@code "CANCELLED"} — identical
 * to the previous string values. No Flyway migration required.
 */
public enum EscalationTaskStatus {

    /** Task is waiting to fire at the scheduled time. */
    PENDING,

    /** Task has fired — escalation notification was sent. */
    ESCALATED,

    /** Task was cancelled (e.g. incident was acknowledged before timeout). */
    CANCELLED
}