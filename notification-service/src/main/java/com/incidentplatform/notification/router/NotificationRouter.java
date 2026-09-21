package com.incidentplatform.notification.router;

import com.incidentplatform.notification.channel.NotificationChannel;
import com.incidentplatform.notification.config.NotificationChannelProperties;
import com.incidentplatform.notification.client.OncallClient;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.shared.domain.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties(NotificationChannelProperties.class)
public class NotificationRouter {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationRouter.class);

    private static final String PRIMARY_ONCALL_ROLE = "PRIMARY";

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
            NotificationChannelProperties properties) {
        this.channelsByName = channels.stream()
                .collect(Collectors.toMap(
                        NotificationChannel::channelName,
                        ch -> ch
                ));
        this.oncallClient = oncallClient;
        this.fallbackEmail = properties.fallback().email();
        this.fallbackSlackChannel = properties.fallback().slackChannel();
        this.fallbackPhone = properties.fallback().phone();
        log.info("NotificationRouter initialized with channels: {}",
                channelsByName.keySet());
    }

    /**
     * Builds the per-channel requests for one notification.
     *
     * <h2>Fixed (backlog #0-1): escalations go to the escalation target</h2>
     * The recipient used to be the PRIMARY on-call for every event type, so an
     * escalation notified the person who had already been alerted instead of
     * the SECONDARY or MANAGER escalation-service chose. For
     * {@code INCIDENT_ESCALATED} the recipient is now the user in
     * {@code escalateTo}, resolved through oncall-service by tenant and user
     * id together.
     *
     * <p>If there is no target, or the lookup finds nobody (not on call any
     * more, or oncall-service unavailable), the escalation goes to the
     * tenant's PRIMARY on-call, exactly as before this change, and only if
     * there is none either to the configured fallback addresses. An earlier
     * draft of this change dropped the {@code getCurrentOncall(tenantId,
     * PRIMARY)} step for escalations and went straight to the fallback
     * addresses; review showed that reversed a safe default: those addresses
     * are platform-wide, not tenant-scoped, so every escalation without a
     * target would have sent that tenant's incident text to a shared
     * destination, and where they are unconfigured it would have reached
     * nobody. The PRIMARY lookup is scoped to the tenant (though tenant-wide
     * across its teams until backlog #0-12, so in a multi-team tenant it can
     * be another team's PRIMARY), and a repeat notification to a PRIMARY who
     * was already alerted is a lesser evil than a lost or misdirected
     * escalation.
     *
     * <p>Known limitation, not fixed here (backlog #0-18): the fallback
     * addresses are still used, for every event type, when no on-call is
     * found, and the message carries the incident title. That is not
     * acceptable for a multi-tenant SaaS and is tracked as a high-priority
     * follow-up. Every other event type keeps the PRIMARY lookup (which is
     * tenant-wide until backlog #0-12 adds the team).
     *
     * @param escalateTo the user an incident was escalated to; only read for
     *                   {@code INCIDENT_ESCALATED}, may be null
     */
    public List<ChannelRequest> route(String eventType,
                                      UUID incidentId,
                                      String tenantId,
                                      Severity severity,
                                      String title,
                                      UUID escalateTo) {

        final Set<String> targetChannels = EVENT_TO_CHANNELS
                .getOrDefault(eventType, Set.of());

        if (targetChannels.isEmpty()) {
            log.debug("No channels configured for event: {}", eventType);
            return List.of();
        }

        final OncallClient.OncallInfo oncall =
                resolveOncall(eventType, incidentId, tenantId, escalateTo);

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

    /**
     * Resolves whose contact details this notification goes to. For
     * {@code INCIDENT_ESCALATED} that is the escalation target when there is
     * one and it can be found; otherwise, and for every other event type, the
     * tenant's PRIMARY on-call. Returns null when nobody was found, which
     * makes {@link #resolveRecipient} use the fallback addresses.
     */
    private OncallClient.OncallInfo resolveOncall(String eventType,
                                                  UUID incidentId,
                                                  String tenantId,
                                                  UUID escalateTo) {
        if (INCIDENT_ESCALATED.equals(eventType)) {
            if (escalateTo == null) {
                log.warn("Escalation without a target user — falling back to " +
                                "the PRIMARY on-call: incidentId={}, tenantId={}",
                        incidentId, tenantId);
            } else {
                final OncallClient.OncallInfo target = oncallClient
                        .findCurrentByUserId(tenantId, escalateTo.toString())
                        .orElse(null);

                if (target != null) {
                    log.debug("Routing escalation to target: tenantId={}, " +
                                    "userId={}, userName={}",
                            tenantId, target.userId(), target.userName());
                    return target;
                }
                log.warn("Escalation target not found (not on call, or " +
                                "oncall-service unavailable) — falling back to " +
                                "the PRIMARY on-call: incidentId={}, tenantId={}, " +
                                "escalateTo={}",
                        incidentId, tenantId, escalateTo);
            }
        }

        final OncallClient.OncallInfo oncall = oncallClient
                .getCurrentOncall(tenantId, PRIMARY_ONCALL_ROLE)
                .orElse(null);

        if (oncall != null) {
            log.debug("Routing to oncall: tenantId={}, userId={}, userName={}",
                    tenantId, oncall.userId(), oncall.userName());
        } else {
            log.warn("No oncall found for tenantId={} — using fallback addresses",
                    tenantId);
        }
        return oncall;
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