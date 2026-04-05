package com.incidentplatform.notification.channel;

import com.incidentplatform.notification.dto.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Kanał Slack — symulacja przez logi.
 *
 * Na produkcji:
 * - Slack Incoming Webhooks API
 * - Slack SDK (com.slack.api:slack-api-client)
 * - Formatowanie wiadomości jako Slack Block Kit JSON
 */
@Component
public class SlackNotificationChannel implements NotificationChannel {

    private static final Logger log =
            LoggerFactory.getLogger(SlackNotificationChannel.class);

    @Value("${notification.channels.slack.enabled:true}")
    private boolean enabled;

    @Value("${notification.channels.slack.channel:#incidents}")
    private String defaultChannel;

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
        // Emoji zależne od severity — wizualny priorytet w Slacku
        final String severityEmoji = switch (request.severity().toUpperCase()) {
            case "CRITICAL" -> "🔴";
            case "HIGH"     -> "🟠";
            case "MEDIUM"   -> "🟡";
            case "LOW"      -> "🟢";
            default         -> "⚪";
        };

        // Na produkcji: restTemplate.postForEntity(webhookUrl, buildSlackPayload(...))
        log.info("""
                [SLACK SIMULATION] Sending Slack message:
                  Channel:  {}
                  Emoji:    {}
                  To:       {}
                  Message:  {}
                  Incident: {} | Tenant: {}
                """,
                defaultChannel,
                severityEmoji,
                request.recipient(),
                request.message(),
                request.incidentId(),
                request.tenantId()
        );
    }
}