package com.incidentplatform.shared.kafka;

/**
 * Kafka did not take a dead-letter copy (backlog #0-96): not acknowledged in
 * time, refused, or the copy could not be built. The caller still holds the
 * failed record and must not let it go: a consumer leaves it for redelivery,
 * ingestion-service answers 503 so the sender retries.
 *
 * <p>An {@link IllegalStateException}, as {@code publishAndWait} threw before
 * this type existed (backlog #0-84).
 */
public class DeadLetterNotStoredException extends IllegalStateException {

    public DeadLetterNotStoredException(String message, Throwable cause) {
        super(message, cause);
    }
}
