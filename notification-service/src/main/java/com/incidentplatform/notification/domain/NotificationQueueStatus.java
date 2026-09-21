package com.incidentplatform.notification.domain;

/**
 * Processing status of a {@link NotificationQueueEntry} outbox entry.
 */
public enum NotificationQueueStatus {

    /** Written by Kafka consumer — awaiting processing by the scheduler. */
    PENDING,

    /** All notification channels processed successfully. */
    SENT,

    /**
     * Processing failed — notification may be partially sent.
     * The {@link NotificationLog} contains per-channel details.
     */
    FAILED,
    /**
     * Nobody in the tenant could be notified (backlog #0-18), see
     * {@link UndeliverableReason}, which is stored in {@code error_message}.
     * The incident text was deliberately not sent anywhere: sending it to a
     * platform-wide destination would hand one tenant's content to a
     * non-member. The operator is told with a content-free alert.
     */
    UNDELIVERABLE
}