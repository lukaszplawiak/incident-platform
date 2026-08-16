package com.incidentplatform.incident.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration for {@code IncidentEventOutboxScheduler} (backlog #36).
 *
 * <h2>YAML configuration</h2>
 * <pre>{@code
 * incident:
 *   event-outbox:
 *     poll-interval-ms: ${INCIDENT_EVENT_OUTBOX_POLL_INTERVAL_MS:2000}
 *     batch-size: ${INCIDENT_EVENT_OUTBOX_BATCH_SIZE:50}
 *     send-timeout: ${INCIDENT_EVENT_OUTBOX_SEND_TIMEOUT:PT5S}
 * }</pre>
 */
@ConfigurationProperties(prefix = "incident.event-outbox")
@Validated
public record IncidentEventOutboxProperties(

        /**
         * How often the scheduler polls for PENDING entries, in
         * milliseconds. See {@code IncidentEventOutboxScheduler}'s Javadoc
         * for why this is kept short relative to other schedulers in this
         * codebase — incident lifecycle events drive real-time escalation
         * and notification timers downstream.
         */
        @Positive(message = "incident.event-outbox.poll-interval-ms must be positive")
        int pollIntervalMs,

        /**
         * Maximum number of PENDING entries processed per poll cycle.
         * Bounds worst-case cycle duration if a large backlog accumulates
         * (e.g. after a Kafka outage) — the next cycle picks up where this
         * one left off, rather than one cycle attempting an unbounded batch.
         */
        @Positive(message = "incident.event-outbox.batch-size must be positive")
        int batchSize,

        /**
         * How long {@code sendRawSync} blocks waiting for the Kafka
         * broker's acknowledgment before treating the send as failed (and
         * leaving the entry PENDING for the next cycle).
         */
        Duration sendTimeout

) {
    public IncidentEventOutboxProperties {
        if (sendTimeout == null) {
            sendTimeout = Duration.ofSeconds(5);
        }
    }

    /** Default used in {@code @Scheduled}'s placeholder — kept in sync manually. */
    public static final int DEFAULT_POLL_INTERVAL_MS = 2000;
}