package com.incidentplatform.notification.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.notification.channel.NotificationException;
import com.incidentplatform.notification.channel.SlackNotificationChannel;
import com.incidentplatform.notification.client.IncidentAckClient;
import com.incidentplatform.notification.client.OncallClient;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.notification.router.NotificationEventTypes;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.domain.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SlackActionService {

    private static final Logger log =
            LoggerFactory.getLogger(SlackActionService.class);

    private static final String SERVICE_NAME = "notification-service";
    private static final String ACK_ACTION_ID = "acknowledge_incident";

    private final IncidentAckClient incidentAckClient;
    private final SlackNotificationChannel slackChannel;
    private final SlackMessageStore messageStore;
    private final OncallClient oncallClient;
    private final ObjectMapper objectMapper;
    private final AuditEventPublisher auditEventPublisher;

    public SlackActionService(IncidentAckClient incidentAckClient,
                              SlackNotificationChannel slackChannel,
                              SlackMessageStore messageStore,
                              OncallClient oncallClient,
                              ObjectMapper objectMapper,
                              AuditEventPublisher auditEventPublisher) {
        this.incidentAckClient = incidentAckClient;
        this.slackChannel = slackChannel;
        this.messageStore = messageStore;
        this.oncallClient = oncallClient;
        this.objectMapper = objectMapper;
        this.auditEventPublisher = auditEventPublisher;
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

        final UUID systemUserId = oncallClient.findBySlackUserId(tenantId, slackUserId)
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

    /**
     * <h2>Fixed (backlog #78): a Slack update failure no longer destroys
     * the tracking data or goes unnoticed</h2>
     * Previously called {@code slackChannel.updateMessageAfterAck(...)}
     * for each channel with no error handling of its own, then
     * unconditionally called {@code messageStore.removeAllForIncident(
     * incidentId)} regardless of whether any of those calls actually
     * succeeded — {@link SlackNotificationChannel}'s own {@code @Retry}
     * fallback silently swallowed the failure (logged a WARN, returned
     * normally), so this method had no way to know. A genuinely
     * transient Slack outage during the ACK flow meant: the Slack
     * message stayed showing the old "unacknowledged" state (with a
     * still-clickable button) permanently, the one piece of tracking
     * data ({@code SlackMessageTs}) a future retry mechanism would need
     * was deleted anyway, and nothing durable recorded that this
     * happened — see {@link SlackNotificationChannel#updateMessageFallback}'s
     * own Javadoc for the other half of this fix.
     *
     * <p>Now: each channel's update is tried independently (one
     * channel's {@link NotificationException} doesn't stop the others
     * from being attempted — same principle {@code NotificationService
     * .processEntry} already applies per-channel for the original send).
     * The tracking row for a channel is only removed on success, so a
     * failed channel's row survives for whatever future retry mechanism
     * might exist. If any channel failed, a single
     * {@code SLACK_ACK_MESSAGE_UPDATE_FAILED} audit event is published —
     * durable, queryable, and precisely named, rather than only visible
     * in application logs.
     */
    void updateSlackMessages(UUID incidentId,
                             String tenantId,
                             String channel,
                             String messageTs,
                             String acknowledgedByName) {
        final NotificationRequest minimalRequest = new NotificationRequest(
                incidentId, tenantId,
                NotificationEventTypes.INCIDENT_ACKNOWLEDGED,
                channel,
                "Incident acknowledged",
                "Incident acknowledged via Slack",
                Severity.LOW,
                "Incident"
        );

        final List<String> failedChannels = new ArrayList<>();

        if (tryUpdateMessage(channel, messageTs, acknowledgedByName, minimalRequest)) {
            messageStore.remove(incidentId, channel);
        } else {
            failedChannels.add(channel);
        }

        final List<String> otherChannels =
                messageStore.findAllChannelsForIncident(incidentId);

        for (final String otherChannel : otherChannels) {
            if (otherChannel.equals(channel)) continue;

            final String ts = messageStore.find(incidentId, otherChannel).orElse(null);
            if (ts == null) continue;

            if (tryUpdateMessage(otherChannel, ts, acknowledgedByName, minimalRequest)) {
                messageStore.remove(incidentId, otherChannel);
            } else {
                failedChannels.add(otherChannel);
            }
        }

        if (failedChannels.isEmpty()) {
            log.info("All Slack messages updated after ACK: incidentId={}, " +
                    "acknowledgedBy={}", incidentId, acknowledgedByName);
            return;
        }

        log.error("Failed to update {} Slack message(s) after ACK — " +
                        "tracking data preserved for these channels, no automatic " +
                        "retry currently exists: incidentId={}, tenant={}, " +
                        "failedChannels={}",
                failedChannels.size(), incidentId, tenantId, failedChannels);

        auditEventPublisher.publishIncident(
                incidentId, tenantId,
                AuditEventTypes.SLACK_ACK_MESSAGE_UPDATE_FAILED, SERVICE_NAME,
                String.format("Incident acknowledged, but the Slack message could " +
                                "not be updated for %d of %d channel(s) — those " +
                                "messages may still show as unacknowledged.",
                        failedChannels.size(),
                        // Fixed: otherChannels (from findAllChannelsForIncident)
                        // already includes the primary channel — that's exactly
                        // why the loop above skips it via
                        // otherChannel.equals(channel) rather than
                        // findAllChannelsForIncident itself excluding it. An
                        // earlier version of this line used
                        // otherChannels.size() + 1, double-counting the primary
                        // channel.
                        otherChannels.size()),
                Map.of("acknowledgedBy", acknowledgedByName,
                        "failedChannels", failedChannels)
        );
    }

    /**
     * @return true if the update succeeded, false if it failed after
     *         {@link SlackNotificationChannel}'s own retries were
     *         exhausted (its fallback rethrows {@link NotificationException}
     *         rather than swallowing it — see that method's own Javadoc).
     */
    private boolean tryUpdateMessage(String channel, String messageTs,
                                     String acknowledgedByName,
                                     NotificationRequest request) {
        try {
            slackChannel.updateMessageAfterAck(
                    channel, messageTs, acknowledgedByName, request);
            return true;
        } catch (NotificationException e) {
            // Already logged with full detail inside updateMessageFallback —
            // this class's own log.error above summarizes across all
            // channels once the whole batch is done, so nothing further
            // is logged here to avoid duplicate noise for the same failure.
            return false;
        }
    }
}