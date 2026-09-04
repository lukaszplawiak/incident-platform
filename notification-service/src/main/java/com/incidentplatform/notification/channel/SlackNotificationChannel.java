package com.incidentplatform.notification.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.notification.config.NotificationChannelProperties;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.notification.slack.SlackMessageStore;
import com.incidentplatform.shared.domain.Severity;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

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

    private final boolean enabled;
    private final String botToken;
    private final String defaultChannel;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final SlackMessageStore messageStore;

    public SlackNotificationChannel(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            NotificationChannelProperties properties,
            SlackMessageStore messageStore) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.enabled        = properties.channels().slack().enabled();
        this.botToken       = properties.channels().slack().botToken();
        this.defaultChannel = properties.channels().slack().channel();
        this.messageStore = messageStore;

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
     * {@code sendWithAckButton} returns the Slack message {@code ts} —
     * needed later to update this specific message via {@code chat.update}
     * once the incident is acknowledged (e.g. from a *different* channel's
     * button, or the web UI). Previously this method called
     * {@code sendWithAckButton} and threw away its return value in both
     * call sites — meaning {@link SlackMessageStore} was never actually
     * populated, ever, in any deployment. The "update every other Slack
     * message for this incident after ACK" loop in
     * {@code SlackActionService.updateSlackMessages} always found nothing
     * to update beyond the one message Slack's own callback payload already
     * identifies directly — not a scaling issue, a wiring bug.
     */
    @Override
    public void send(NotificationRequest request) {
        final String defaultChannelTs = sendWithAckButton(defaultChannel, request);
        messageStore.save(request.incidentId(), defaultChannel,
                request.tenantId(), defaultChannelTs);

        if (isSlackUserId(request.recipient())) {
            final String dmTs = sendWithAckButton(request.recipient(), request);
            messageStore.save(request.incidentId(), request.recipient(),
                    request.tenantId(), dmTs);

            log.info("Slack DM with ACK button sent to on-call: " +
                            "userId={}, incidentId={}",
                    request.recipient(), request.incidentId());
        }
    }

    @Retry(name = "slack", fallbackMethod = "sendWithAckButtonFallback")
    public String sendWithAckButton(String channel,
                                    NotificationRequest request) {
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

        final String ts = extractTs(responseBody);

        log.info("Slack message sent with ACK button: " +
                        "channel={}, incidentId={}, ts={}",
                channel, request.incidentId(), ts);

        return ts;
    }

    @Retry(name = "slack", fallbackMethod = "updateMessageFallback")
    public void updateMessageAfterAck(String channel,
                                      String messageTs,
                                      String acknowledgedByName,
                                      NotificationRequest originalRequest) {
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

        restClient.post()
                .uri(slackApiUpdateUrl)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + botToken)
                .body(payload)
                .retrieve()
                .toBodilessEntity();

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
                Map.of("type", "divider"),
                Map.of(
                        "type", "actions",
                        "elements", List.of(
                                Map.of(
                                        "type", "button",
                                        "text", Map.of(
                                                "type", "plain_text",
                                                "text", "✅ Acknowledge",
                                                "emoji", true
                                        ),
                                        "action_id", "acknowledge_incident",
                                        "value", String.format("%s|%s",
                                                request.incidentId(),
                                                request.tenantId()),
                                        "style", "primary"
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

    void sendWithAckButtonFallback(String channel,
                                   NotificationRequest request,
                                   Exception cause) {
        log.error("Slack notification failed after all retries: " +
                        "channel={}, incidentId={}, error={}",
                channel, request.incidentId(), cause.getMessage());
        throw new NotificationException(
                "SLACK", channel,
                String.format("Slack API failed after retries for " +
                        "channel=%s: %s", channel, cause.getMessage()),
                cause);
    }

    /**
     * Fixed (backlog #78): previously logged this failure and returned
     * normally (void, no signal of any kind) — the caller
     * ({@code SlackActionService.updateSlackMessages}) had no way to know
     * this failed, and unconditionally deleted the {@code SlackMessageTs}
     * tracking row for every channel regardless of whether its update
     * actually succeeded, destroying the one piece of data a future retry
     * mechanism would need. Now rethrows (as {@link NotificationException},
     * matching the exact exception type {@link #sendWithAckButtonFallback}
     * already uses elsewhere in this same class for the identical
     * "retries exhausted, tell the caller" situation) so the caller can
     * make an informed decision per channel instead of silently assuming
     * success.
     */
    void updateMessageFallback(String channel,
                               String messageTs,
                               String acknowledgedByName,
                               NotificationRequest originalRequest,
                               Exception cause) {
        log.warn("Failed to update Slack message after ACK: " +
                        "channel={}, ts={}, error={}",
                channel, messageTs, cause.getMessage());
        throw new NotificationException(
                "SLACK", channel,
                "Failed to update Slack message after ACK: " + cause.getMessage(),
                cause);
    }

    private boolean isSlackUserId(String recipient) {
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

    private String extractTs(String responseBody) {
        try {
            return objectMapper.readTree(responseBody)
                    .path("ts")
                    .asText(null);
        } catch (Exception e) {
            log.warn("Failed to extract ts from Slack response: {}",
                    e.getMessage());
            return null;
        }
    }
}