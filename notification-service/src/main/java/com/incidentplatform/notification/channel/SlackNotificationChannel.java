package com.incidentplatform.notification.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.notification.client.SlackWorkspaceClient;
import com.incidentplatform.notification.client.SlackWorkspaceLookupUnavailableException;
import com.incidentplatform.notification.config.NotificationChannelProperties;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.notification.slack.SlackMessageStore;
import com.incidentplatform.shared.domain.Severity;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class SlackNotificationChannel implements NotificationChannel {

    private static final Logger log =
            LoggerFactory.getLogger(SlackNotificationChannel.class);

    // Built from the configurable apiBaseUrl in the constructor, not a
    // hardcoded constant — lets tests point this class at a local WireMock
    // server instead of the real Slack API. Defaults to the real Slack API
    // via application.yml (notification.channels.slack.api-base-url).
    private final String slackApiPostUrl;
    private final String slackApiUpdateUrl;

    /** The counter's channel tag for a refused broadcast, apart from the delivery itself (backlog #0-93). */
    static final String BROADCAST_CHANNEL_TAG = "SLACK_BROADCAST";

    /** Slack's {@code error} codes it documents as worth trying again later. */
    private static final Set<String> TRANSIENT_ERRORS =
            Set.of("internal_error", "fatal_error", "service_unavailable", "request_timeout");

    /** Slack's {@code error} codes for a bot token it no longer accepts (second review of #0-93). */
    private static final Set<String> AUTH_ERRORS = Set.of(
            "invalid_auth", "not_authed", "token_revoked", "token_expired", "account_inactive");

    private final boolean enabled;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final SlackMessageStore messageStore;
    private final SlackWorkspaceClient slackWorkspaceClient;
    private final MeterRegistry meterRegistry;

    public SlackNotificationChannel(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            NotificationChannelProperties properties,
            SlackMessageStore messageStore,
            SlackWorkspaceClient slackWorkspaceClient,
            MeterRegistry meterRegistry) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.enabled = properties.channels().slack().enabled();
        this.messageStore = messageStore;
        this.slackWorkspaceClient = slackWorkspaceClient;
        this.meterRegistry = meterRegistry;

        final String apiBaseUrl = properties.channels().slack().apiBaseUrl();
        this.slackApiPostUrl = apiBaseUrl + "/chat.postMessage";
        this.slackApiUpdateUrl = apiBaseUrl + "/chat.update";
    }

    @Override
    public String channelName() {
        return "SLACK";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sends the incident notification to the default channel, and
     * additionally as a DM if the recipient is a Slack user ID.
     *
     * <h2>Fixed: the returned ts was previously discarded entirely</h2>
     * {@code postIncidentMessage} returns the Slack message {@code ts} —
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

    /**
     * {@link #postIncidentMessage} as {@link #send} calls it: on this object,
     * not through Spring's proxy, so neither its {@code @Retry} nor its
     * fallback applies (found in the second review of #0-93; see backlog
     * #0-103). An HTTP error is therefore classified here, as the fallback
     * would, instead of reaching the caller raw and being recorded as an
     * unexpected error.
     */
    private String post(String channel, NotificationRequest request, String botToken) {
        try {
            return postIncidentMessage(channel, request, botToken);
        } catch (RestClientException e) {
            throw failure(channel, e);
        }
    }

    @Retry(name = "slack", fallbackMethod = "postIncidentMessageFallback")
    public String postIncidentMessage(String channel,
                                    NotificationRequest request,
                                    String botToken) {
        final String severityEmoji = resolveSeverityEmoji(request.severity());

        final Map<String, Object> payload = Map.of(
                "channel", channel,
                "blocks", buildBlocks(request, severityEmoji),
                "text", String.format("%s [%s] %s",
                        severityEmoji,
                        request.severity().name(),
                        request.subject())
        );

        final String responseBody = restClient.post()
                .uri(slackApiPostUrl)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + botToken)
                .body(payload)
                .retrieve()
                .body(String.class);

        final String ts = requireOk(channel, responseBody).path("ts").asText(null);

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
    @Retry(name = "slack", fallbackMethod = "updateMessageFallback")
    public void updateMessageAfterAck(String channel,
                                      String messageTs,
                                      String acknowledgedByName,
                                      NotificationRequest originalRequest,
                                      String botToken) {
        final String severityEmoji =
                resolveSeverityEmoji(originalRequest.severity());

        final Map<String, Object> payload = Map.of(
                "channel", channel,
                "ts", messageTs,
                "blocks", buildAcknowledgedBlocks(
                        originalRequest, severityEmoji, acknowledgedByName),
                "text", String.format("%s [%s] %s — Acknowledged by %s",
                        severityEmoji,
                        originalRequest.severity().name(),
                        originalRequest.subject(),
                        acknowledgedByName)
        );

        final String responseBody = restClient.post()
                .uri(slackApiUpdateUrl)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + botToken)
                .body(payload)
                .retrieve()
                .body(String.class);
        requireOk(channel, responseBody);

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

    void postIncidentMessageFallback(String channel,
                                   NotificationRequest request,
                                   String botToken,
                                   Exception cause) {
        final NotificationException failure = failure(channel, cause);
        log.error("Slack notification failed: channel={}, incidentId={}, reason={}",
                channel, request.incidentId(), failure.recordedText(), cause);
        throw failure;
    }

    /**
     * Fixed (backlog #78): previously logged this failure and returned
     * normally (void, no signal of any kind) — the caller
     * ({@code SlackActionService.updateSlackMessages}) had no way to know
     * this failed, and unconditionally deleted the {@code SlackMessageTs}
     * tracking row for every channel regardless of whether its update
     * actually succeeded, destroying the one piece of data a future retry
     * mechanism would need. Now rethrows (as {@link NotificationException},
     * matching the exact exception type {@link #postIncidentMessageFallback}
     * already uses elsewhere in this same class for the identical
     * "retries exhausted, tell the caller" situation) so the caller can
     * make an informed decision per channel instead of silently assuming
     * success.
     */
    void updateMessageFallback(String channel,
                               String messageTs,
                               String acknowledgedByName,
                               NotificationRequest originalRequest,
                               String botToken,
                               Exception cause) {
        final NotificationException failure = failure(channel, cause);
        log.warn("Failed to update Slack message after ACK: channel={}, ts={}, reason={}",
                channel, messageTs, failure.recordedText(), cause);
        throw failure;
    }

    /**
     * The answer of a Slack Web API call, which must say {@code "ok": true}
     * (backlog #0-93). Slack reports most failures (a channel the bot is not
     * in, a revoked token, an archived channel) as HTTP 200 with
     * {@code {"ok":false,"error":"<code>"}}; this used to be read as a
     * delivery, so a message that never left was recorded as SENT. Now it is
     * a {@link NotificationException} carrying Slack's code, which is a fixed
     * vocabulary and safe to record once checked; Slack's free-text fields
     * are not read. Not retried: the retry covers network errors and 5xx, and
     * the answers Slack documents as transient are few.
     */
    private JsonNode requireOk(String channel, String responseBody) {
        final JsonNode answer;
        try {
            answer = responseBody == null ? null : objectMapper.readTree(responseBody);
        } catch (JsonProcessingException e) {
            throw new NotificationException("SLACK", channel,
                    NotificationFailureReason.SLACK_UNAVAILABLE, "unreadable_response", e);
        }
        if (answer == null || !answer.path("ok").isBoolean()) {
            throw new NotificationException("SLACK", channel,
                    NotificationFailureReason.SLACK_UNAVAILABLE, "unreadable_response", null);
        }
        if (!answer.path("ok").asBoolean()) {
            final String code = answer.path("error").asText(null);
            final NotificationException failure =
                    new NotificationException("SLACK", channel, reasonFor(code), code, null);
            if (code != null && failure.detail() == null && log.isDebugEnabled()) {
                // Not a plain code, so not recorded (review: leave a trace for the
                // operator). Shown with anything but printable ASCII replaced and
                // cut, as it came from outside and #0-94 has not escaped log lines.
                log.debug("Slack answered ok:false with an error that is not a plain code: channel={}, error={}",
                        channel, printable(code));
            }
            throw failure;
        }
        return answer;
    }

    private static String printable(String value) {
        final String cut = value.length() > 64 ? value.substring(0, 64) + "..." : value;
        return cut.replaceAll("[^\\x20-\\x7E]", "?");
    }

    private static NotificationFailureReason reasonFor(String slackError) {
        if (slackError == null) {
            // ok:false without saying why (second review: Set.of(...).contains(null)
            // throws, which recorded it as an unexpected error).
            return NotificationFailureReason.SLACK_REJECTED;
        }
        if ("ratelimited".equals(slackError)) {
            return NotificationFailureReason.SLACK_RATE_LIMITED;
        }
        if (AUTH_ERRORS.contains(slackError)) {
            return NotificationFailureReason.SLACK_AUTH_FAILED;
        }
        return TRANSIENT_ERRORS.contains(slackError)
                ? NotificationFailureReason.SLACK_UNAVAILABLE
                : NotificationFailureReason.SLACK_REJECTED;
    }

    /**
     * What a failed Slack call (after its retries) becomes (backlog #0-93).
     * Resilience4j calls the fallback for every exception, not only the
     * retried ones, so a {@link NotificationException} already classified by
     * {@link #requireOk} passes through as it is. An HTTP error is classified
     * by its status alone, never its body; an exception that is not the HTTP
     * client's is rethrown unchanged, so the caller records it by its type.
     */
    static NotificationException failure(String channel, Exception cause) {
        if (cause instanceof NotificationException classified) {
            return classified;
        }
        if (cause instanceof HttpClientErrorException.Unauthorized) {
            return new NotificationException("SLACK", channel,
                    NotificationFailureReason.SLACK_AUTH_FAILED, "http_401", cause);
        }
        if (cause instanceof HttpClientErrorException.TooManyRequests) {
            return new NotificationException("SLACK", channel,
                    NotificationFailureReason.SLACK_RATE_LIMITED, "http_429", cause);
        }
        if (cause instanceof HttpClientErrorException client) {
            return new NotificationException("SLACK", channel, NotificationFailureReason.SLACK_REJECTED,
                    "http_" + client.getStatusCode().value(), cause);
        }
        if (cause instanceof HttpServerErrorException server) {
            return new NotificationException("SLACK", channel, NotificationFailureReason.SLACK_UNAVAILABLE,
                    "http_" + server.getStatusCode().value(), cause);
        }
        if (cause instanceof RestClientException) {
            return new NotificationException("SLACK", channel,
                    NotificationFailureReason.SLACK_UNAVAILABLE, cause);
        }
        if (cause instanceof RuntimeException unexpected) {
            throw unexpected;
        }
        throw new IllegalStateException("Slack call failed", cause);
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