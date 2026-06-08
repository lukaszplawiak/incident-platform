package com.incidentplatform.notification.domain;

/**
 * Delivery status of a {@link NotificationLog} entry.
 *
 * <p>Used as {@code @Enumerated(EnumType.STRING)} so the database stores
 * the same string values as before — no Flyway migration required.
 */
public enum NotificationLogStatus {

    /** Notification was delivered successfully. */
    SENT,

    /** Delivery failed after all retries. */
    FAILED,

    /** Notification was skipped (e.g. idempotency check — already sent). */
    SKIPPED
}