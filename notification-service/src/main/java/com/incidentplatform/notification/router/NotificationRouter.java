package com.incidentplatform.notification.router;

import com.incidentplatform.notification.channel.NotificationChannel;
import com.incidentplatform.notification.dto.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Router powiadomień — tłumaczy event na listę żądań wysyłki.
 *
 * Odpowiada na pytania:
 * 1. Dla jakiego eventu wysyłamy powiadomienie?
 * 2. Przez jakie kanały?
 * 3. Do kogo?
 * 4. Z jaką treścią?
 *
 * W prawdziwym systemie "do kogo" pochodzi z bazy:
 * on-call schedule, user preferences, escalation policy.
 */
@Component
public class NotificationRouter {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationRouter.class);

    private static final Map<String, Set<String>> EVENT_TO_CHANNELS =
            Map.of(
                    "IncidentOpenedEvent",        Set.of("EMAIL", "SLACK"),
                    "IncidentEscalatedEvent",     Set.of("EMAIL", "SLACK", "SMS"),
                    "IncidentAcknowledgedEvent",  Set.of("SLACK"),
                    "IncidentResolvedEvent",      Set.of("EMAIL", "SLACK"),
                    "IncidentClosedEvent",        Set.of("EMAIL")
            );

    private final Map<String, NotificationChannel> channelsByName;

    public NotificationRouter(List<NotificationChannel> channels) {
        this.channelsByName = channels.stream()
                .collect(Collectors.toMap(
                        NotificationChannel::channelName,
                        ch -> ch
                ));
        log.info("NotificationRouter initialized with channels: {}",
                channelsByName.keySet());
    }

    public List<ChannelRequest> route(String eventType,
                                      UUID incidentId,
                                      String tenantId,
                                      String severity,
                                      String title) {

        final Set<String> targetChannels = EVENT_TO_CHANNELS
                .getOrDefault(eventType, Set.of());

        if (targetChannels.isEmpty()) {
            log.debug("No channels configured for event: {}", eventType);
            return List.of();
        }

        return targetChannels.stream()
                .map(channelName -> {
                    final NotificationChannel channel =
                            channelsByName.get(channelName);

                    if (channel == null || !channel.isEnabled()) {
                        log.debug("Channel {} not available, skipping", channelName);
                        return null;
                    }

                    // Symulujemy odbiorcę — w production pobieramy z bazy
                    final String recipient = resolveRecipient(
                            channelName, tenantId, severity);

                    final NotificationRequest request = new NotificationRequest(
                            incidentId,
                            tenantId,
                            eventType,
                            recipient,
                            buildSubject(eventType, title, severity),
                            buildMessage(eventType, title, severity, incidentId),
                            severity,
                            title
                    );

                    return new ChannelRequest(channel, request);
                })
                .filter(cr -> cr != null)
                .toList();
    }

    private String resolveRecipient(String channelName,
                                    String tenantId,
                                    String severity) {
        return switch (channelName) {
            case "EMAIL" -> "oncall@" + tenantId + ".example.com";
            case "SLACK" -> "@oncall-" + tenantId;
            case "SMS"   -> "+48100200300";
            default      -> "unknown";
        };
    }

    private String buildSubject(String eventType, String title,
                                String severity) {
        return switch (eventType) {
            case "IncidentOpenedEvent"    ->
                    "[" + severity + "] New incident: " + title;
            case "IncidentEscalatedEvent" ->
                    "[ESCALATED][" + severity + "] " + title;
            case "IncidentResolvedEvent"  ->
                    "[RESOLVED] " + title;
            case "IncidentAcknowledgedEvent" ->
                    "[ACK] " + title;
            case "IncidentClosedEvent"    ->
                    "[CLOSED] " + title;
            default -> "Incident update: " + title;
        };
    }

    private String buildMessage(String eventType, String title,
                                String severity, UUID incidentId) {
        return switch (eventType) {
            case "IncidentOpenedEvent" ->
                    String.format("New %s incident opened: '%s' (ID: %s). " +
                                    "Please acknowledge immediately.",
                            severity, title, incidentId);
            case "IncidentEscalatedEvent" ->
                    String.format("ESCALATION: Incident '%s' (ID: %s) has been " +
                                    "escalated due to no acknowledgment. " +
                                    "Severity: %s. Immediate action required.",
                            title, incidentId, severity);
            case "IncidentResolvedEvent" ->
                    String.format("Incident '%s' (ID: %s) has been resolved.",
                            title, incidentId);
            case "IncidentAcknowledgedEvent" ->
                    String.format("Incident '%s' (ID: %s) has been acknowledged " +
                                    "and is being worked on.",
                            title, incidentId);
            case "IncidentClosedEvent" ->
                    String.format("Incident '%s' (ID: %s) has been closed. " +
                                    "Postmortem may follow.",
                            title, incidentId);
            default ->
                    String.format("Update on incident '%s' (ID: %s).",
                            title, incidentId);
        };
    }

    public record ChannelRequest(
            NotificationChannel channel,
            NotificationRequest request
    ) {}
}