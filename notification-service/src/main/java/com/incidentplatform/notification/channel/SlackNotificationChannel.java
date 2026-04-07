package com.incidentplatform.notification.channel;

import com.incidentplatform.notification.dto.NotificationRequest;
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

    @Value("${notification.channels.slack.enabled:true}")
    private boolean enabled;

    @Value("${notification.channels.slack.webhook-url}")
    private String webhookUrl;

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
        final String severityEmoji = switch (request.severity().toUpperCase()) {
            case "CRITICAL" -> "🔴";
            case "HIGH"     -> "🟠";
            case "MEDIUM"   -> "🟡";
            case "LOW"      -> "🟢";
            default         -> "⚪";
        };

        final String text = String.format(
                "%s *%s*\n>%s\n>Incident ID: `%s` | Tenant: `%s`",
                severityEmoji,
                request.subject(),
                request.message(),
                request.incidentId(),
                request.tenantId()
        );

        final Map<String, String> payload = Map.of("text", text);

        try {
            restClient.post()
                    .uri(webhookUrl)
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Slack notification sent: recipient={}, incidentId={}",
                    request.recipient(), request.incidentId());

        } catch (Exception e) {
            throw new NotificationException(
                    "SLACK",
                    request.recipient(),
                    "Slack webhook call failed: " + e.getMessage(),
                    e
            );
        }
    }
}