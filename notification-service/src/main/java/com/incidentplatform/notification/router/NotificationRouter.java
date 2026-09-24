package com.incidentplatform.notification.router;

import com.incidentplatform.notification.channel.NotificationChannel;
import com.incidentplatform.notification.channel.SlackNotificationChannel;
import com.incidentplatform.notification.config.NotificationChannelProperties;
import com.incidentplatform.notification.client.OncallClient;
import com.incidentplatform.notification.client.OncallLookupUnavailableException;
import com.incidentplatform.notification.client.SlackWorkspaceClient;
import com.incidentplatform.notification.client.SlackWorkspaceLookupUnavailableException;
import com.incidentplatform.notification.domain.UndeliverableReason;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.shared.domain.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
    private final SlackWorkspaceClient slackWorkspaceClient;

    public NotificationRouter(
            List<NotificationChannel> channels,
            OncallClient oncallClient,
            SlackWorkspaceClient slackWorkspaceClient) {
        this.channelsByName = channels.stream()
                .collect(Collectors.toMap(
                        NotificationChannel::channelName,
                        ch -> ch
                ));
        this.oncallClient = oncallClient;
        this.slackWorkspaceClient = slackWorkspaceClient;
        log.info("NotificationRouter initialized with channels: {}",
                channelsByName.keySet());
    }

    /**
     * Builds the per-channel requests for one notification, or says that
     * nobody in the tenant can be notified.
     *
     * <h2>Fixed (backlog #0-1): escalations go to the escalation target</h2>
     * The recipient used to be the PRIMARY on-call for every event type, so an
     * escalation notified the person who had already been alerted instead of
     * the SECONDARY or MANAGER escalation-service chose. For
     * {@code INCIDENT_ESCALATED} the recipient is now the user in
     * {@code escalateTo}, resolved through oncall-service by tenant and user
     * id together. If there is no target, or nobody is found for it, the
     * escalation goes to the tenant's PRIMARY on-call, as before. (An earlier
     * draft dropped the {@code getCurrentOncall(tenantId, PRIMARY)} step; that
     * reversed a safe default and was undone.) That PRIMARY is tenant-wide
     * across its teams until backlog #0-12, so in a multi-team tenant it can be
     * another team's PRIMARY.
     *
     * <h2>Fixed (backlog #0-18): tenant content only reaches members of the tenant</h2>
     * When nobody is found, the recipient used to be the platform-wide
     * {@code notification.fallback.*} addresses, and a found contact that lacked
     * a channel got the fallback address for that channel. Both sent one
     * tenant's incident title, id and severity to a destination that is not a
     * member of that tenant. There is no fallback address any more: a channel
     * the contact has no address for is skipped, and if nobody can be reached
     * the result is {@link Routing#undeliverable}. The caller parks the entry
     * as UNDELIVERABLE and tells the operator with a content-free alert. This is
     * what PagerDuty-style systems do: an incident nobody can be assigned to is
     * a visible error, not a message to a shared inbox. A tenant-owned fallback
     * contact is backlog #0-20.
     *
     * <h2>Fixed (backlog #0-12): PRIMARY lookup is now team-scoped</h2>
     * The PRIMARY fallback — used by every event type, and by
     * {@code INCIDENT_ESCALATED} when there is no target or the target isn't
     * found — now passes the incident's {@code teamId} to oncall-service, so a
     * multi-team tenant no longer risks notifying another team's PRIMARY.
     * {@code teamId} is {@code null} for incidents with no team assignment
     * (manually created, or an {@code Integration} without one), in which case
     * the lookup is still tenant-wide, exactly as before this fix.
     *
     * @param escalateTo the user an incident was escalated to; only read for
     *                   {@code INCIDENT_ESCALATED}, may be null
     * @param teamId     the team the incident belongs to, or null; scopes the
     *                   PRIMARY fallback lookup (backlog #0-12)
     * @throws OncallLookupUnavailableException if oncall-service cannot answer
     *         a lookup (backlog #0-19); the scheduler retries the entry
     * @throws SlackWorkspaceLookupUnavailableException only if auth-service
     *         cannot answer the Slack-workspace lookup <em>and</em> Slack was the
     *         only channel that could have reached the contact (backlog #0-21);
     *         with any other reachable channel, Slack is skipped instead
     */
    public Routing route(String eventType,
                         UUID incidentId,
                         String tenantId,
                         Severity severity,
                         String title,
                         UUID escalateTo,
                         UUID teamId) {

        final Set<String> targetChannels = EVENT_TO_CHANNELS
                .getOrDefault(eventType, Set.of());

        if (targetChannels.isEmpty()) {
            log.debug("No channels configured for event: {}", eventType);
            return Routing.nothingToSend();
        }

        final List<NotificationChannel> enabledChannels = targetChannels.stream()
                .map(channelsByName::get)
                .filter(ch -> ch != null && ch.isEnabled())
                .toList();

        if (enabledChannels.isEmpty()) {
            log.debug("No enabled channel for event: {}", eventType);
            return Routing.nothingToSend();
        }

        final OncallClient.OncallInfo oncall =
                resolveOncall(eventType, incidentId, tenantId, escalateTo, teamId);

        if (oncall == null) {
            log.warn("Nobody on call in the tenant — notification is " +
                            "undeliverable: incidentId={}, tenantId={}, eventType={}",
                    incidentId, tenantId, eventType);
            return Routing.undeliverable(UndeliverableReason.NO_ONCALL);
        }

        final List<ChannelRequest> requests = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        SlackWorkspaceLookupUnavailableException slackLookupFailure = null;

        for (final NotificationChannel channel : enabledChannels) {
            final String recipient;
            try {
                recipient = recipientFor(channel.channelName(), oncall, tenantId);
            } catch (SlackWorkspaceLookupUnavailableException e) {
                // Backlog #0-21: auth-service could not say whether this tenant has a
                // Slack workspace. Skip only Slack and let the other channels go out
                // now — the lookup decides a channel, not the recipient, so unlike an
                // oncall-service outage (#0-19) it must not hold back email/SMS. The
                // fallback already recorded ClientFallbackMetrics; this Slack message
                // is not retried (backlog #0-32).
                log.warn("auth-service unavailable — Slack skipped for this " +
                                "notification: incidentId={}, tenantId={}, eventType={}",
                        incidentId, tenantId, eventType);
                slackLookupFailure = e;
                skipped.add(channel.channelName());
                continue;
            }

            if (recipient == null) {
                skipped.add(channel.channelName());
                continue;
            }

            requests.add(new ChannelRequest(channel, new NotificationRequest(
                    incidentId,
                    tenantId,
                    eventType,
                    recipient,
                    buildSubject(eventType, title, severity),
                    buildMessage(eventType, title, severity, incidentId),
                    severity,
                    title
            )));
        }

        if (requests.isEmpty() && slackLookupFailure != null) {
            // Slack might have been the only way to reach this person, and we
            // don't know yet. Declaring the entry undeliverable now would repeat
            // the #0-19 mistake (an outage read as a permanent answer); with no
            // other request there is nothing that retrying would delay, so let
            // the scheduler keep the entry PENDING within its retry window.
            throw slackLookupFailure;
        }

        if (requests.isEmpty()) {
            log.warn("On-call user has no address on any enabled channel — " +
                            "notification is undeliverable: incidentId={}, " +
                            "tenantId={}, eventType={}, userId={}",
                    incidentId, tenantId, eventType, oncall.userId());
            return Routing.undeliverable(
                    UndeliverableReason.NO_REACHABLE_CHANNEL, skipped);
        }

        return Routing.send(requests, skipped);
    }

    /**
     * Resolves whose contact details this notification goes to. For
     * {@code INCIDENT_ESCALATED} that is the escalation target when there is
     * one and it can be found; otherwise, and for every other event type, the
     * PRIMARY on-call — scoped to {@code teamId} when the incident has one
     * (backlog #0-12), tenant-wide otherwise. Returns null when nobody was
     * found.
     */
    private OncallClient.OncallInfo resolveOncall(String eventType,
                                                  UUID incidentId,
                                                  String tenantId,
                                                  UUID escalateTo,
                                                  UUID teamId) {
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
                log.warn("Escalation target is not on call — falling back to " +
                                "the PRIMARY on-call: incidentId={}, tenantId={}, " +
                                "escalateTo={}",
                        incidentId, tenantId, escalateTo);
            }
        }

        final OncallClient.OncallInfo oncall = oncallClient
                .getCurrentOncall(tenantId, teamId, PRIMARY_ONCALL_ROLE)
                .orElse(null);

        if (oncall != null) {
            log.debug("Routing to oncall: tenantId={}, userId={}, userName={}",
                    tenantId, oncall.userId(), oncall.userName());
        }
        return oncall;
    }

    /**
     * The on-call user's own address for a channel, or null if they have none
     * (in which case the channel is skipped, never replaced by a shared one).
     *
     * <h2>Fixed (backlog #0-21): SLACK also requires an active workspace for the tenant</h2>
     * Previously this only checked whether the on-call user had a usable Slack id — a
     * tenant with no Slack workspace installed at all would still build a {@code
     * ChannelRequest} for SLACK, which {@code SlackNotificationChannel.send()} would
     * then fail loudly on. Checked here instead, same "skip, don't build a doomed
     * request" philosophy already applied to the Slack-id check right below it.
     *
     * <p>"No workspace" (empty) returns null here, like any other missing address. An
     * auth-service outage is <em>not</em> folded into that: it surfaces as
     * {@link SlackWorkspaceLookupUnavailableException}, which {@link #route} handles
     * separately (skip Slack, send the rest; hold the entry only when Slack was the
     * sole channel).
     *
     * @throws SlackWorkspaceLookupUnavailableException for SLACK when auth-service
     *         cannot answer
     */
    private String recipientFor(String channelName,
                                OncallClient.OncallInfo oncall,
                                String tenantId) {
        return switch (channelName) {
            case EMAIL -> oncall.email() != null && !oncall.email().isBlank()
                    ? oncall.email() : null;
            // The same predicate the Slack channel uses to DM: an id it would silently
            // ignore (an Enterprise Grid "W…" id, a channel name) is no address. The
            // workspace lookup comes last so a contact without a usable Slack id never
            // costs an auth-service call — and an auth-service outage can't affect a
            // notification that would never have used Slack anyway. The router does not
            // know the tenant's broadcast flag, so a contact without a valid Slack id
            // still gets no Slack request here, and therefore no shared-channel post.
            case SLACK -> oncall.hasDm()
                    && SlackNotificationChannel.isSlackUserId(oncall.slackUserId())
                    && slackWorkspaceClient.getWorkspace(tenantId).isPresent()
                    ? oncall.slackUserId() : null;
            case SMS   -> oncall.hasSms() ? oncall.phone() : null;
            default    -> null;
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

    /**
     * Outcome of {@link #route}: requests to send, nothing to send (no channel
     * is configured or enabled for the event), or undeliverable (nobody in the
     * tenant can be notified, backlog #0-18).
     *
     * @param skippedChannels enabled channels the on-call user has no address
     *                        for; they are skipped, never replaced by a shared
     *                        destination, and the caller makes that visible
     */
    public record Routing(List<ChannelRequest> requests,
                          List<String> skippedChannels,
                          UndeliverableReason undeliverableReason) {

        public static Routing send(List<ChannelRequest> requests) {
            return new Routing(requests, List.of(), null);
        }

        public static Routing send(List<ChannelRequest> requests,
                                   List<String> skippedChannels) {
            return new Routing(requests, List.copyOf(skippedChannels), null);
        }

        public static Routing nothingToSend() {
            return new Routing(List.of(), List.of(), null);
        }

        public static Routing undeliverable(UndeliverableReason reason) {
            return new Routing(List.of(), List.of(), reason);
        }

        public static Routing undeliverable(UndeliverableReason reason,
                                            List<String> skippedChannels) {
            return new Routing(List.of(), List.copyOf(skippedChannels), reason);
        }

        public boolean isUndeliverable() {
            return undeliverableReason != null;
        }
    }
}
