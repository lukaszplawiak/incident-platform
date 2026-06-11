package com.incidentplatform.notification.router;

import com.incidentplatform.shared.events.IncidentEventTypes;

/**
 * Notification routing event type constants.
 *
 * <p>Delegates to {@link IncidentEventTypes} in {@code shared} so both the
 * event producer (incident-service) and the notification consumer use the
 * same string values from a single source of truth.
 *
 * <p>Kept as a separate class so {@link NotificationRouter} and
 * {@link com.incidentplatform.notification.slack.SlackActionService}
 * can continue using static imports without changing their import paths.
 */
public final class NotificationEventTypes {

    public static final String INCIDENT_OPENED       = IncidentEventTypes.INCIDENT_OPENED;
    public static final String INCIDENT_ESCALATED    = IncidentEventTypes.INCIDENT_ESCALATED;
    public static final String INCIDENT_ACKNOWLEDGED = IncidentEventTypes.INCIDENT_ACKNOWLEDGED;
    public static final String INCIDENT_RESOLVED     = IncidentEventTypes.INCIDENT_RESOLVED;
    public static final String INCIDENT_CLOSED       = IncidentEventTypes.INCIDENT_CLOSED;

    private NotificationEventTypes() {}
}