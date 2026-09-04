package com.incidentplatform.notification.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.notification.channel.NotificationException;
import com.incidentplatform.notification.channel.SlackNotificationChannel;
import com.incidentplatform.notification.client.IncidentAckClient;
import com.incidentplatform.notification.client.OncallClient;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

/**
 * Tests for {@link SlackActionService#updateSlackMessages} — previously had
 * no coverage at all (no {@code SlackActionServiceTest} existed before
 * backlog #78). Focused specifically on this method's per-channel error
 * handling, made directly testable by relaxing its visibility from
 * {@code private} to package-private, matching the same precedent already
 * used for {@link SlackNotificationChannel}'s own fallback methods in this
 * exact area of the codebase.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SlackActionService.updateSlackMessages")
class SlackActionServiceTest {

    @Mock private IncidentAckClient incidentAckClient;
    @Mock private SlackNotificationChannel slackChannel;
    @Mock private SlackMessageStore messageStore;
    @Mock private OncallClient oncallClient;
    @Mock private AuditEventPublisher auditEventPublisher;

    private SlackActionService service;

    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final String TENANT_ID = "test-tenant";
    private static final String CHANNEL = "#incidents";
    private static final String MESSAGE_TS = "1234567890.123456";

    @BeforeEach
    void setUp() {
        service = new SlackActionService(
                incidentAckClient, slackChannel, messageStore,
                oncallClient, new ObjectMapper(), auditEventPublisher);
    }

    @Nested
    @DisplayName("all channels succeed")
    class AllSucceed {

        @Test
        @DisplayName("removes the tracking row for each successfully-updated " +
                "channel and does not publish an audit event")
        void removesTrackingRowsAndPublishesNothing() {
            given(messageStore.findAllChannelsForIncident(INCIDENT_ID))
                    .willReturn(List.of(CHANNEL));

            service.updateSlackMessages(
                    INCIDENT_ID, TENANT_ID, CHANNEL, MESSAGE_TS, "Jane Doe");

            then(slackChannel).should().updateMessageAfterAck(
                    eq(CHANNEL), eq(MESSAGE_TS), eq("Jane Doe"), any(NotificationRequest.class));
            then(messageStore).should().remove(INCIDENT_ID, CHANNEL);
            then(messageStore).should(never()).removeAllForIncident(any());
            then(auditEventPublisher).should(never())
                    .publishIncident(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("updates every tracked channel, not just the one the " +
                "button was clicked in")
        void updatesEveryTrackedChannel() {
            final String secondChannel = "#oncall-team-a";
            given(messageStore.findAllChannelsForIncident(INCIDENT_ID))
                    .willReturn(List.of(CHANNEL, secondChannel));
            given(messageStore.find(INCIDENT_ID, secondChannel))
                    .willReturn(Optional.of("9999.111111"));

            service.updateSlackMessages(
                    INCIDENT_ID, TENANT_ID, CHANNEL, MESSAGE_TS, "Jane Doe");

            then(slackChannel).should().updateMessageAfterAck(
                    eq(CHANNEL), eq(MESSAGE_TS), anyString(), any());
            then(slackChannel).should().updateMessageAfterAck(
                    eq(secondChannel), eq("9999.111111"), anyString(), any());
            then(messageStore).should().remove(INCIDENT_ID, CHANNEL);
            then(messageStore).should().remove(INCIDENT_ID, secondChannel);
        }
    }

    /**
     * The actual regression coverage for backlog #78.
     */
    @Nested
    @DisplayName("one channel fails (backlog #78)")
    class OneChannelFails {

        @Test
        @DisplayName("does not remove the tracking row for the failed channel, " +
                "but does remove it for a channel that succeeded")
        void preservesTrackingRowOnlyForFailedChannel() {
            final String secondChannel = "#oncall-team-a";
            given(messageStore.findAllChannelsForIncident(INCIDENT_ID))
                    .willReturn(List.of(CHANNEL, secondChannel));
            given(messageStore.find(INCIDENT_ID, secondChannel))
                    .willReturn(Optional.of("9999.111111"));

            // The primary channel (button-click channel) fails; the second succeeds.
            willThrow(new NotificationException("SLACK", CHANNEL, "Slack API down"))
                    .given(slackChannel).updateMessageAfterAck(
                            eq(CHANNEL), eq(MESSAGE_TS), anyString(), any());

            service.updateSlackMessages(
                    INCIDENT_ID, TENANT_ID, CHANNEL, MESSAGE_TS, "Jane Doe");

            // then — failed channel's row survives, succeeded channel's row is removed
            then(messageStore).should(never()).remove(INCIDENT_ID, CHANNEL);
            then(messageStore).should().remove(INCIDENT_ID, secondChannel);
            then(messageStore).should(never()).removeAllForIncident(any());
        }

        @Test
        @DisplayName("still attempts every other channel after one fails")
        void stillAttemptsOtherChannelsAfterOneFails() {
            final String secondChannel = "#oncall-team-a";
            given(messageStore.findAllChannelsForIncident(INCIDENT_ID))
                    .willReturn(List.of(CHANNEL, secondChannel));
            given(messageStore.find(INCIDENT_ID, secondChannel))
                    .willReturn(Optional.of("9999.111111"));

            willThrow(new NotificationException("SLACK", CHANNEL, "Slack API down"))
                    .given(slackChannel).updateMessageAfterAck(
                            eq(CHANNEL), eq(MESSAGE_TS), anyString(), any());

            service.updateSlackMessages(
                    INCIDENT_ID, TENANT_ID, CHANNEL, MESSAGE_TS, "Jane Doe");

            // then — the second channel was still attempted despite the first's failure
            then(slackChannel).should().updateMessageAfterAck(
                    eq(secondChannel), eq("9999.111111"), anyString(), any());
        }

        @Test
        @DisplayName("publishes SLACK_ACK_MESSAGE_UPDATE_FAILED naming the failed channel")
        void publishesAuditEventNamingFailedChannel() {
            given(messageStore.findAllChannelsForIncident(INCIDENT_ID))
                    .willReturn(List.of(CHANNEL));
            willThrow(new NotificationException("SLACK", CHANNEL, "Slack API down"))
                    .given(slackChannel).updateMessageAfterAck(
                            eq(CHANNEL), eq(MESSAGE_TS), anyString(), any());

            service.updateSlackMessages(
                    INCIDENT_ID, TENANT_ID, CHANNEL, MESSAGE_TS, "Jane Doe");

            @SuppressWarnings("unchecked")
            final ArgumentCaptor<Map<String, Object>> metadataCaptor =
                    ArgumentCaptor.forClass(Map.class);
            then(auditEventPublisher).should().publishIncident(
                    eq(INCIDENT_ID), eq(TENANT_ID),
                    eq(AuditEventTypes.SLACK_ACK_MESSAGE_UPDATE_FAILED),
                    anyString(), anyString(), metadataCaptor.capture());

            @SuppressWarnings("unchecked")
            final List<String> failedChannels =
                    (List<String>) metadataCaptor.getValue().get("failedChannels");
            assertThat(failedChannels).containsExactly(CHANNEL);
        }

        @Test
        @DisplayName("does not publish an audit event at all when every channel succeeds " +
                "in a multi-channel batch")
        void doesNotPublishWhenAllChannelsSucceedInBatch() {
            final String secondChannel = "#oncall-team-a";
            given(messageStore.findAllChannelsForIncident(INCIDENT_ID))
                    .willReturn(List.of(CHANNEL, secondChannel));
            given(messageStore.find(INCIDENT_ID, secondChannel))
                    .willReturn(Optional.of("9999.111111"));

            service.updateSlackMessages(
                    INCIDENT_ID, TENANT_ID, CHANNEL, MESSAGE_TS, "Jane Doe");

            then(auditEventPublisher).should(never())
                    .publishIncident(any(), any(), any(), any(), any(), any());
        }
    }
}