package com.incidentplatform.notification.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.notification.channel.SlackNotificationChannel;
import com.incidentplatform.notification.client.IncidentAckClient;
import com.incidentplatform.notification.client.OncallClient;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.shared.domain.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class SlackActionService {

    private static final Logger log =
            LoggerFactory.getLogger(SlackActionService.class);

    private static final String ACK_ACTION_ID = "acknowledge_incident";

    private final IncidentAckClient incidentAckClient;
    private final SlackNotificationChannel slackChannel;
    private final SlackMessageStore messageStore;
    private final OncallClient oncallClient;
    private final ObjectMapper objectMapper;

    public SlackActionService(IncidentAckClient incidentAckClient,
                              SlackNotificationChannel slackChannel,
                              SlackMessageStore messageStore,
                              OncallClient oncallClient,
                              ObjectMapper objectMapper) {
        this.incidentAckClient = incidentAckClient;
        this.slackChannel = slackChannel;
        this.messageStore = messageStore;
        this.oncallClient = oncallClient;
        this.objectMapper = objectMapper;
    }

    @Async("slackTaskExecutor")
    public void processAction(String slackPayload) {
        try {
            final JsonNode payload = objectMapper.readTree(slackPayload);
            final String type = payload.path("type").asText();

            if (!"block_actions".equals(type)) {
                log.debug("Ignoring Slack action type: {}", type);
                return;
            }

            final JsonNode actions = payload.path("actions");
            if (!actions.isArray() || actions.isEmpty()) {
                log.warn("Slack webhook received with no actions");
                return;
            }

            for (final JsonNode action : actions) {
                if (ACK_ACTION_ID.equals(action.path("action_id").asText())) {
                    processAcknowledgeAction(action, payload);
                }
            }

        } catch (Exception e) {
            log.error("Failed to process Slack action: {}", e.getMessage(), e);
        }
    }

    private void processAcknowledgeAction(JsonNode action, JsonNode payload) {
        final String value = action.path("value").asText();
        final String[] parts = value.split("\\|");

        if (parts.length != 2) {
            log.warn("Invalid action value format: {}", value);
            return;
        }

        final UUID incidentId;
        try {
            incidentId = UUID.fromString(parts[0]);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid incidentId in action value: {}", parts[0]);
            return;
        }

        final String tenantId = parts[1];

        final JsonNode user = payload.path("user");
        final String slackUserId = user.path("id").asText("unknown");
        final String slackUserName = user.path("name").asText("unknown");

        log.info("Processing ACK: incidentId={}, tenant={}, slackUser={}",
                incidentId, tenantId, slackUserName);

        final UUID systemUserId = oncallClient.findBySlackUserId(slackUserId)
                .map(info -> {
                    log.debug("Mapped slackUserId={} to systemUserId={}",
                            slackUserId, info.userId());
                    return UUID.fromString(info.userId());
                })
                .orElseGet(() -> {
                    log.warn("No system user found for slackUserId={} — " +
                            "using deterministic UUID as fallback", slackUserId);
                    return UUID.nameUUIDFromBytes(slackUserId.getBytes());
                });

        final boolean acknowledged = incidentAckClient.acknowledgeIncident(
                incidentId, tenantId, systemUserId);

        if (!acknowledged) {
            log.error("Failed to acknowledge incident: incidentId={}, tenant={}",
                    incidentId, tenantId);
            return;
        }

        final JsonNode container = payload.path("container");
        final String channel = container.path("channel_id").asText();
        final String messageTs = container.path("message_ts").asText();

        updateSlackMessages(incidentId, tenantId, channel, messageTs,
                slackUserName);
    }

    private void updateSlackMessages(UUID incidentId,
                                     String tenantId,
                                     String channel,
                                     String messageTs,
                                     String acknowledgedByName) {
        final NotificationRequest minimalRequest = new NotificationRequest(
                incidentId, tenantId,
                "IncidentAcknowledgedEvent",
                channel,
                "Incident acknowledged",
                "Incident acknowledged via Slack",
                Severity.LOW,
                "Incident"
        );

        slackChannel.updateMessageAfterAck(
                channel, messageTs, acknowledgedByName, minimalRequest);

        final List<String> otherChannels =
                messageStore.findAllChannelsForIncident(incidentId);

        for (final String otherChannel : otherChannels) {
            if (otherChannel.equals(channel)) continue;

            messageStore.find(incidentId, otherChannel).ifPresent(ts ->
                    slackChannel.updateMessageAfterAck(
                            otherChannel, ts, acknowledgedByName, minimalRequest)
            );
        }

        messageStore.removeAllForIncident(incidentId);

        log.info("All Slack messages updated after ACK: incidentId={}, " +
                "acknowledgedBy={}", incidentId, acknowledgedByName);
    }
}