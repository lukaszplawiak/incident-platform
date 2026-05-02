package com.incidentplatform.notification.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.shared.domain.Severity;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class SlackNotificationChannel implements NotificationChannel {

    private static final Logger log =
            LoggerFactory.getLogger(SlackNotificationChannel.class);

    private static final String SLACK_API_POST =
            "https://slack.com/api/chat.postMessage";
    public static final String SLACK_API_UPDATE =
            "https://slack.com/api/chat.update";

    @Value("${notification.channels.slack.enabled:true}")
    private boolean enabled;

    @Value("${notification.channels.slack.bot-token}")
    private String botToken;

    @Value("${notification.channels.slack.channel:#incidents}")
    private String defaultChannel;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public SlackNotificationChannel(RestClient.Builder restClientBuilder,
                                    ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String channelName() {
        return "SLACK";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void send(NotificationRequest request) {
        sendWithAckButton(defaultChannel, request);

        if (isSlackUserId(request.recipient())) {
            sendWithAckButton(request.recipient(), request);
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
                .uri(SLACK_API_POST)
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
                .uri(SLACK_API_UPDATE)
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
                // Zamiast przycisku — informacja o ACK
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

    void updateMessageFallback(String channel,
                               String messageTs,
                               String acknowledgedByName,
                               NotificationRequest originalRequest,
                               Exception cause) {
        log.warn("Failed to update Slack message after ACK: " +
                        "channel={}, ts={}, error={}",
                channel, messageTs, cause.getMessage());
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