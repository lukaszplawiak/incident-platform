package com.incidentplatform.notification.channel;

import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.shared.domain.Severity;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class SlackNotificationChannel implements NotificationChannel {

    private static final Logger log =
            LoggerFactory.getLogger(SlackNotificationChannel.class);

    private static final String SLACK_API_URL =
            "https://slack.com/api/chat.postMessage";

    @Value("${notification.channels.slack.enabled:true}")
    private boolean enabled;

    @Value("${notification.channels.slack.bot-token}")
    private String botToken;

    @Value("${notification.channels.slack.channel:#incidents}")
    private String defaultChannel;

    private final RestClient restClient;

    public SlackNotificationChannel(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
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
        final String text = buildText(request);

        sendToSlack(defaultChannel, text, request);

        if (isSlackUserId(request.recipient())) {
            sendToSlack(request.recipient(), text, request);
            log.info("Slack DM sent to on-call: userId={}, incidentId={}",
                    request.recipient(), request.incidentId());
        } else {
            log.debug("Recipient is not a Slack User ID — skipping DM: " +
                    "recipient={}", request.recipient());
        }
    }

    @Retry(name = "slack", fallbackMethod = "sendToSlackFallback")
    void sendToSlack(String channel, String text, NotificationRequest request) {
        final Map<String, String> payload = Map.of(
                "channel", channel,
                "text", text
        );

        restClient.post()
                .uri(SLACK_API_URL)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + botToken)
                .body(payload)
                .retrieve()
                .toBodilessEntity();

        log.info("Slack message sent: channel={}, incidentId={}",
                channel, request.incidentId());
    }

    void sendToSlackFallback(String channel, String text,
                             NotificationRequest request, Exception cause) {
        log.error("Slack notification failed after all retry attempts: " +
                        "channel={}, incidentId={}, error={}",
                channel, request.incidentId(), cause.getMessage());

        throw new NotificationException(
                "SLACK",
                channel,
                String.format("Slack API call failed after retries for " +
                        "channel=%s: %s", channel, cause.getMessage()),
                cause
        );
    }

    private boolean isSlackUserId(String recipient) {
        return recipient != null
                && !recipient.isBlank()
                && recipient.startsWith("U");
    }

    private String buildText(NotificationRequest request) {
        final String severityEmoji = switch (request.severity()) {
            case CRITICAL -> "🔴";
            case HIGH     -> "🟠";
            case MEDIUM   -> "🟡";
            case LOW      -> "🟢";
        };

        return String.format(
                "%s *%s*\n>%s\n>Incident ID: `%s` | Tenant: `%s`",
                severityEmoji,
                request.subject(),
                request.message(),
                request.incidentId(),
                request.tenantId()
        );
    }
}