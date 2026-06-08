package com.incidentplatform.shared.audit;

/**
 * Change source constants used in {@link com.incidentplatform.shared.dto.AuditEventMessage}
 * and incident history records to indicate what triggered a state change.
 *
 * <p>Used as the {@code changeSource} field in {@code IncidentHistory}
 * to record whether a change came from an API call, a Kafka consumer,
 * or an automatic system process.
 */
public final class ChangeSource {

    /** Change triggered by a REST API call from a user or service. */
    public static final String REST_API       = "REST_API";

    /** Change triggered by a Kafka consumer processing an incoming event. */
    public static final String KAFKA_CONSUMER = "KAFKA_CONSUMER";

    /** Change triggered automatically by the system (e.g. alert resolution). */
    public static final String AUTO_RESOLVE   = "AUTO_RESOLVE";

    private ChangeSource() {}
}