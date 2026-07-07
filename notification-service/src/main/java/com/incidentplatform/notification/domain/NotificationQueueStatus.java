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
    FAILED
}