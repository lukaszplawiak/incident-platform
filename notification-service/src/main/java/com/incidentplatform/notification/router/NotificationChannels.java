package com.incidentplatform.notification.router;

/**
 * Notification channel name constants.
 *
 * <p>Channel names are used as keys in the channel registry map and in
 * the event-to-channels routing table. Centralised here to prevent
 * typos from silently dropping notifications at runtime.
 */
public final class NotificationChannels {

    public static final String EMAIL = "EMAIL";
    public static final String SLACK = "SLACK";
    public static final String SMS   = "SMS";

    private NotificationChannels() {}
}