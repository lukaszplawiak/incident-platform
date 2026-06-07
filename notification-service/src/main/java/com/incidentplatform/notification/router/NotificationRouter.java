package com.incidentplatform.notification.router;

import com.incidentplatform.notification.channel.NotificationChannel;
import com.incidentplatform.notification.client.OncallClient;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.shared.domain.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.incidentplatform.notification.router.NotificationChannels.EMAIL;
import static com.incidentplatform.notification.router.NotificationChannels.SLACK;
import static com.incidentplatform.notification.router.NotificationChannels.SMS;
import static com.incidentplatform.notification.router.NotificationEventTypes.INCIDENT_ACKNOWLEDGED;
import static com.incidentplatform.notification.router.NotificationEventTypes.INCIDENT_CLOSED;
import static com.incidentplatform.notification.router.NotificationEventTypes.INCIDENT_ESCALATED;
import static com.incidentplatform.notification.router.NotificationEventTypes.INCIDENT_OPENED;
import static com.incidentplatform.notification.router.NotificationEventTypes.INCIDENT_RESOLVED;

@Component
public class NotificationRouter {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationRouter.class);

    private static final Map<String, Set<String>> EVENT_TO_CHANNELS =
            Map.of(
                    INCIDENT_OPENED,       Set.of(EMAIL, SLACK),
                    INCIDENT_ESCALATED,    Set.of(EMAIL, SLACK, SMS),
                    INCIDENT_ACKNOWLEDGED, Set.of(SLACK),
                    INCIDENT_RESOLVED,     Set.of(EMAIL, SLACK),
                    INCIDENT_CLOSED,       Set.of(EMAIL)
            );

    private final Map<String, NotificationChannel> channelsByName;
    private final OncallClient oncallClient;
    private final String fallbackEmail;
    private final String fallbackSlackChannel;
    private final String fallbackPhone;

    public NotificationRouter(
            List<NotificationChannel> channels,
            OncallClient oncallClient,
            @Value("${notification.fallback.email:oncall@example.com}") String fallbackEmail,
            @Value("${notification.fallback.slack-channel:#incidents}") String fallbackSlackChannel,
            @Value("${notification.fallback.phone:}") String fallbackPhone) {
        this.channelsByName = channels.stream()
                .collect(Collectors.toMap(
                        NotificationChannel::channelName,
                        ch -> ch
                ));
        this.oncallClient = oncallClient;
        this.fallbackEmail = fallbackEmail;
        this.fallbackSlackChannel = fallbackSlackChannel;
        this.fallbackPhone = fallbackPhone;
        log.info("NotificationRouter initialized with channels: {}",
                channelsByName.keySet());
    }

    public List<ChannelRequest> route(String eventType,
                                      UUID incidentId,
                                      String tenantId,
                                      Severity severity,
                                      String title) {

        final Set<String> targetChannels = EVENT_TO_CHANNELS
                .getOrDefault(eventType, Set.of());

        if (targetChannels.isEmpty()) {
            log.debug("No channels configured for event: {}", eventType);
            return List.of();
        }

        final OncallClient.OncallInfo oncall = oncallClient
                .getCurrentOncall(tenantId, "PRIMARY")
                .orElse(null);

        if (oncall != null) {
            log.debug("Routing to oncall: tenantId={}, userId={}, userName={}",
                    tenantId, oncall.userId(), oncall.userName());
        } else {
            log.warn("No oncall found for tenantId={} — using fallback addresses",
                    tenantId);
        }

        return targetChannels.stream()
                .map(channelName -> {
                    final NotificationChannel channel =
                            channelsByName.get(channelName);

                    if (channel == null || !channel.isEnabled()) {
                        log.debug("Channel {} not available, skipping",
                                channelName);
                        return null;
                    }

                    final String recipient = resolveRecipient(
                            channelName, tenantId, oncall);

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
                .filter(Objects::nonNull)
                .toList();
    }

    private String resolveRecipient(String channelName,
                                    String tenantId,
                                    OncallClient.OncallInfo oncall) {
        if (oncall != null) {
            return switch (channelName) {
                case EMAIL -> oncall.email() != null
                        ? oncall.email() : fallbackEmail;
                case SLACK -> oncall.hasDm()
                        ? oncall.slackUserId() : fallbackSlackChannel;
                case SMS   -> oncall.hasSms()
                        ? oncall.phone() : fallbackPhone;
                default -> "unknown";
            };
        }

        return switch (channelName) {
            case EMAIL -> fallbackEmail;
            case SLACK -> fallbackSlackChannel;
            case SMS   -> fallbackPhone;
            default -> "unknown";
        };
    }

    private String buildSubject(String eventType, String title, Severity severity) {
        return switch (eventType) {
            case INCIDENT_OPENED       ->
                    "[" + severity.name() + "] New incident: " + title;
            case INCIDENT_ESCALATED    ->
                    "[ESCALATED][" + severity.name() + "] " + title;
            case INCIDENT_RESOLVED     ->
                    "[RESOLVED] " + title;
            case INCIDENT_ACKNOWLEDGED ->
                    "[ACK] " + title;
            case INCIDENT_CLOSED       ->
                    "[CLOSED] " + title;
            default -> "Incident update: " + title;
        };
    }

    private String buildMessage(String eventType, String title,
                                Severity severity, UUID incidentId) {
        return switch (eventType) {
            case INCIDENT_OPENED ->
                    String.format("New %s incident opened: '%s' (ID: %s). " +
                                    "Please acknowledge immediately.",
                            severity.name(), title, incidentId);
            case INCIDENT_ESCALATED ->
                    String.format("ESCALATION: Incident '%s' (ID: %s) has been " +
                                    "escalated due to no acknowledgment. " +
                                    "Severity: %s. Immediate action required.",
                            title, incidentId, severity.name());
            case INCIDENT_RESOLVED ->
                    String.format("Incident '%s' (ID: %s) has been resolved.",
                            title, incidentId);
            case INCIDENT_ACKNOWLEDGED ->
                    String.format("Incident '%s' (ID: %s) has been acknowledged " +
                                    "and is being worked on.",
                            title, incidentId);
            case INCIDENT_CLOSED ->
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