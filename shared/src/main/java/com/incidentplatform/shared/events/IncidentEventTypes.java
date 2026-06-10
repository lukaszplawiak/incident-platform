package com.incidentplatform.shared.events;

/**
 * Incident lifecycle event type constants used as Kafka message header values.
 *
 * <p>The header name {@link #HEADER_NAME} is set by the producer
 * ({@code IncidentEventPublisher}) on every message and read by consumers
 * ({@code IncidentEventConsumer} in notification-service, etc.) to route
 * messages without inspecting the payload structure.
 *
 * <p>Using a Kafka header rather than a payload field keeps the domain
 * records ({@link IncidentOpenedEvent}, etc.) clean — they contain only
 * business data, not transport metadata.
 *
 * <p>This class lives in {@code shared} so both producers and all consumers
 * reference the same constants. {@code NotificationEventTypes} in
 * {@code notification-service} delegates here.
 */
public final class IncidentEventTypes {

    /**
     * Kafka message header key carrying the event type string.
     * Set by {@code IncidentEventPublisher}, read by all consumers.
     */
    public static final String HEADER_NAME = "X-Event-Type";

    // ── Event type values ───────────────────────────────────────────────────
    public static final String INCIDENT_OPENED       = "IncidentOpenedEvent";
    public static final String INCIDENT_ACKNOWLEDGED = "IncidentAcknowledgedEvent";
    public static final String INCIDENT_RESOLVED     = "IncidentResolvedEvent";
    public static final String INCIDENT_ESCALATED    = "IncidentEscalatedEvent";
    public static final String INCIDENT_CLOSED       = "IncidentClosedEvent";

    private IncidentEventTypes() {}
}