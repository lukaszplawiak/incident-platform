package com.incidentplatform.shared.kafka;

/**
 * A record whose payload is not JSON ({@link TenantKafkaRecordResolver#parseJson}).
 * Its message names the parser's problem by type only, never the payload
 * (Jackson's own message quotes it), so it may go into a dead-letter reason
 * and a log line ({@link KafkaFailures#reason}, backlog #0-96).
 *
 * <p>An {@link IllegalArgumentException}, as the resolver threw before, so every
 * consumer's poison-pill {@code catch} still takes it.
 */
public class UnreadableRecordException extends IllegalArgumentException {

    public UnreadableRecordException(String message, Throwable cause) {
        super(message, cause);
    }
}
