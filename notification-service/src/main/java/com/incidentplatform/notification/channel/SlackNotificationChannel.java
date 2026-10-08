package com.incidentplatform.notification.channel;

import com.incidentplatform.notification.client.SlackWorkspaceClient;
import com.incidentplatform.notification.client.SlackWorkspaceLookupUnavailableException;
import com.incidentplatform.notification.config.NotificationChannelProperties;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.notification.slack.SlackMessageStore;
import com.incidentplatform.shared.domain.Severity;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class SlackNotificationChannel implements NotificationChannel {

    private static final Logger log =
            LoggerFactory.getLogger(SlackNotificationChannel.class);

    /** The counter's channel tag for a refused broadcast, apart from the delivery itself (backlog #0-93). */
    static final String BROADCAST_CHANNEL_TAG = "SLACK_BROADCAST";

    /** Slack calls one {@link #send} makes at most: the broadcast and the on-call DM. */
    public static final int MAX_CALLS_PER_SEND = 2;

    private final boolean enabled;
    private final SlackApiClient slackApi;
    private final SlackMessageStore messageStore;
    private final SlackWorkspaceClient slackWorkspaceClient;
    private final MeterRegistry meterRegistry;

    /**
     * Backlog #0-103: the Slack Web API calls, their {@code @Retry} and its
     * fallback moved to {@link SlackApiClient}, a bean of its own, so that
     * {@link #send} reaches them through Spring's proxy; they used to be
     * methods of this class, called on {@code this}, and never retried.
     */
    public SlackNotificationChannel(
            SlackApiClient slackApi,
            NotificationChannelProperties properties,
            SlackMessageStore messageStore,
            SlackWorkspaceClient slackWorkspaceClient,
            MeterRegistry meterRegistry) {
        this.slackApi = slackApi;
        this.enabled = properties.channels().slack().enabled();
        this.messageStore = messageStore;
        this.slackWorkspaceClient = slackWorkspaceClient;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String channelName() {
        return "SLACK";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /** The broadcast and the DM, each with its retries (review of backlog #0-103). */
    @Override
    public Duration worstCaseSendTime() {
        return enabled ? slackApi.worstCaseCall().multipliedBy(MAX_CALLS_PER_SEND) : Duration.ZERO;
    }

    /**
     * Sends the incident notification to the default channel, and
     * additionally as a DM if the recipient is a Slack user ID.
     *
     * <h2>Fixed: the returned ts was previously discarded entirely</h2>
     * {@link SlackApiClient#postMessage} returns the Slack message {@code ts} —
     * needed later to update this specific message via {@code chat.update}
     * once the incident is acknowledged (e.g. from a *different* channel's
     * button, or the web UI). Previously this method called
     * {@code postIncidentMessage} and threw away its return value in both
     * call sites — meaning {@link SlackMessageStore} was never actually
     * populated, ever, in any deployment. The "update every other Slack
     * message for this incident after ACK" loop in
     * {@code SlackActionService.updateSlackMessages} always found nothing
     * to update beyond the one message Slack's own callback payload already
     * identifies directly — not a scaling issue, a wiring bug. The ts is
     * still stored now that the message carries no ACK button (backlog
     * #0-35): the update path returns unchanged with the OAuth install.
     *
     * <h2>Fixed (backlog #0-21): the bot token/channel/broadcast flag are
     * per-tenant now, not a single global config</h2>
     * Resolved via {@link SlackWorkspaceClient} instead of a field held
     * since construction. {@code NotificationRouter} already checks a
     * workspace exists for the tenant before building this channel's
     * request (same "skip, don't build a request that posts nothing"
     * philosophy as the Slack-user-id check), so reaching here with no
     * workspace configured means a caller bypassed the router — the same
     * defensive fail-loud case the broadcast-disabled/no-Slack-id branch
     * below already covers. The router's lookup is normally served from
     * {@code CachingSlackWorkspaceClient}, so this second read costs no HTTP
     * call in the common case.
     */
    @Override
    public void send(NotificationRequest request) {
        final SlackWorkspaceClient.SlackWorkspaceInfo workspace;
        try {
            workspace = slackWorkspaceClient.getWorkspace(request.tenantId())
                    .orElseThrow(() -> new NotificationException("SLACK", request.recipient(),
                            NotificationFailureReason.SLACK_WORKSPACE_MISSING, null));
        } catch (SlackWorkspaceLookupUnavailableException e) {
            // The router saw a workspace (possibly from cache) but auth-service
            // failed now. Converted to NotificationException so processEntry
            // records this channel as FAILED with an audit event — the same
            // outcome as a Slack API outage — instead of the generic "unexpected
            // error" path. Kept distinct from "no workspace" in the message.
            throw new NotificationException("SLACK", request.recipient(),
                    NotificationFailureReason.SLACK_WORKSPACE_UNAVAILABLE, e);
        }

        // Fixed (backlog #0-18): the message used to be posted to the one shared
        // channel for every notification of every tenant, whatever the recipient.
        // In a multi-tenant deployment that hands each tenant's incident text to a
        // destination that is not a member of that tenant, so it is now opt-in
        // (backlog #0-21: opted in per tenant, on that tenant's own workspace).
        final boolean broadcastEnabled = workspace.broadcastEnabled()
                && workspace.defaultChannel() != null && !workspace.defaultChannel().isBlank();

        if (broadcastEnabled) {
            try {
                final String defaultChannelTs = post(
                        workspace.defaultChannel(), request, workspace.botToken());
                messageStore.save(request.incidentId(), workspace.defaultChannel(),
                        request.tenantId(), defaultChannelTs);
            } catch (NotificationException broadcastFailed) {
                // Backlog #0-93 (second review): the broadcast must not cost the
                // on-call their DM. Before #0-93 a refused broadcast (a channel
                // archived, or one the bot was removed from) passed as a success,
                // so the DM always went; now that it fails, it would have stopped
                // the DM too. So the DM is still sent, and the notification counts
                // as delivered when it is: it reached the person it is for. The
                // broadcast's failure is logged with its reason and counted in
                // notification.channel.failed under its own channel tag
                // (SLACK_BROADCAST, third review: the log line alone was the only
                // trace); not a notification_log row, as the log keeps one row
                // per channel and the Slack row is the delivery's. With no DM to
                // send, the broadcast was the whole delivery, and its failure is
                // the channel's.
                if (!isSlackUserId(request.recipient())) {
                    throw broadcastFailed;
                }
                meterRegistry.counter("notification.channel.failed", "channel", BROADCAST_CHANNEL_TAG,
                        "reason", broadcastFailed.reason().name()).increment();
                final String line = "Slack broadcast failed, the on-call DM is still sent: channel={}, "
                        + "incidentId={}, reason={}";
                if (broadcastFailed.reason().permanent()) {
                    log.warn(line, workspace.defaultChannel(), request.incidentId(),
                            broadcastFailed.recordedText(), broadcastFailed.getCause());
                } else {
                    log.error(line, workspace.defaultChannel(), request.incidentId(),
                            broadcastFailed.recordedText(), broadcastFailed.getCause());
                }
            }
        }

        if (isSlackUserId(request.recipient())) {
            final String dmTs = post(
                    request.recipient(), request, workspace.botToken());
            messageStore.save(request.incidentId(), request.recipient(),
                    request.tenantId(), dmTs);

            log.info("Slack DM sent to on-call: " +
                            "userId={}, incidentId={}",
                    request.recipient(), request.incidentId());
        } else if (!broadcastEnabled) {
            // Nothing was posted: the broadcast is off (or unconfigured) and the
            // recipient is not a Slack user id, so no DM either. Returning
            // normally would let the caller record a SENT notification (and a
            // NOTIFICATION_SENT audit event) for a message that never left. The
            // router already skips a recipient that is not a Slack user id, so
            // this only guards a caller that bypasses it; fail loudly rather
            // than report a delivery.
            throw new NotificationException("SLACK", request.recipient(),
                    NotificationFailureReason.SLACK_NOTHING_POSTED, null);
        }
    }

    /** Posts the incident message to {@code channel} and returns its {@code ts}. */
    private String post(String channel, NotificationRequest request, String botToken) {
        final String severityEmoji = resolveSeverityEmoji(request.severity());
        final String ts = slackApi.postMessage(channel, Map.of(
                "blocks", buildBlocks(request, severityEmoji),
                "text", String.format("%s [%s] %s",
                        severityEmoji,
                        request.severity().name(),
                        request.subject())
        ), botToken);

        log.info("Slack incident message sent: " +
                        "channel={}, incidentId={}, ts={}",
                channel, request.incidentId(), ts);

        return ts;
    }

    /**
     * Fixed (backlog #0-21): {@code botToken} is now a parameter, not a
     * field held since construction — the caller ({@code
     * SlackActionService}, which already carries {@code tenantId} through
     * the ACK round-trip) resolves the tenant's own token via {@link
     * SlackWorkspaceClient} and passes it in, instead of this method always
     * using one global token regardless of which tenant's incident is being
     * acknowledged.
     */
    public void updateMessageAfterAck(String channel,
                                      String messageTs,
                                      String acknowledgedByName,
                                      NotificationRequest originalRequest,
                                      String botToken) {
        final String severityEmoji =
                resolveSeverityEmoji(originalRequest.severity());

        slackApi.updateMessage(channel, messageTs, Map.of(
                "blocks", buildAcknowledgedBlocks(
                        originalRequest, severityEmoji, acknowledgedByName),
                "text", String.format("%s [%s] %s — Acknowledged by %s",
                        severityEmoji,
                        originalRequest.severity().name(),
                        originalRequest.subject(),
                        acknowledgedByName)
        ), botToken);

        log.info("Slack message updated after ACK: channel={}, ts={}, " +
                "acknowledgedBy={}", channel, messageTs, acknowledgedByName);
    }

    private List<Map<String, Object>> buildBlocks(NotificationRequest request,
                                                  String severityEmoji) {
        return List.of(
                Map.of(
                        "type", "section",
                        "text", Map.of(
                                "type", "mrkdwn",
                                "text", String.format(
                                        "%s *%s*\n>%s\n>Incident ID: `%s` | " +
                                                "Tenant: `%s`",
                                        severityEmoji,
                                        request.subject(),
                                        request.message(),
                                        request.incidentId(),
                                        request.tenantId())
                        )
                ),
                // Backlog #0-21/#0-35: no "Acknowledge" button any more. A tenant
                // connects Slack by pasting a bot token from its own Slack App, so
                // a click is signed with that App's signing secret, which
                // SlackSignatureVerifier (one platform-wide secret) can never
                // match: the button always ended in a 401 and an error in Slack.
                // A plain pointer to the app instead. The /api/v1/slack/actions
                // webhook, SlackActionService and updateMessageAfterAck are kept
                // unchanged: with the OAuth "Add to Slack" install (#0-35) every
                // workspace uses the platform's own App and the button returns.
                Map.of(
                        "type", "context",
                        "elements", List.of(
                                Map.of(
                                        "type", "mrkdwn",
                                        "text", "Acknowledge this incident in the Incident Platform app."
                                )
                        )
                )
        );
    }

    private List<Map<String, Object>> buildAcknowledgedBlocks(
            NotificationRequest request,
            String severityEmoji,
            String acknowledgedByName) {
        return List.of(
                Map.of(
                        "type", "section",
                        "text", Map.of(
                                "type", "mrkdwn",
                                "text", String.format(
                                        "%s *%s*\n>%s\n>Incident ID: `%s` | " +
                                                "Tenant: `%s`",
                                        severityEmoji,
                                        request.subject(),
                                        request.message(),
                                        request.incidentId(),
                                        request.tenantId())
                        )
                ),
                Map.of("type", "divider"),
                Map.of(
                        "type", "context",
                        "elements", List.of(
                                Map.of(
                                        "type", "mrkdwn",
                                        "text", String.format(
                                                "✅ Acknowledged by *%s*",
                                                acknowledgedByName)
                                )
                        )
                )
        );
    }

    /**
     * Whether {@code recipient} is a Slack user id that {@link #send} will DM.
     * Public and static so that {@code NotificationRouter} decides with the very
     * same predicate: a recipient this rejects has no Slack address, and the router
     * skips the channel instead of building a request that would silently post
     * nothing (backlog #0-18).
     */
    public static boolean isSlackUserId(String recipient) {
        return recipient != null
                && !recipient.isBlank()
                && recipient.startsWith("U");
    }

    private String resolveSeverityEmoji(Severity severity) {
        return switch (severity) {
            case CRITICAL -> "🔴";
            case HIGH     -> "🟠";
            case MEDIUM   -> "🟡";
            case LOW      -> "🟢";
        };
    }
}