package com.incidentplatform.notification.router;

/**
 * Event type string constants used to route notifications.
 *
 * <p>Centralised here so changes to event names require a single edit
 * and are caught by the compiler — unlike scattered string literals
 * which silently break routing when mistyped.
 */
public final class NotificationEventTypes {

    public static final String INCIDENT_OPENED       = "IncidentOpenedEvent";
    public static final String INCIDENT_ESCALATED    = "IncidentEscalatedEvent";
    public static final String INCIDENT_ACKNOWLEDGED = "IncidentAcknowledgedEvent";
    public static final String INCIDENT_RESOLVED     = "IncidentResolvedEvent";
    public static final String INCIDENT_CLOSED       = "IncidentClosedEvent";

    private NotificationEventTypes() {}
}