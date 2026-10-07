package com.incidentplatform.notification.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.notification.channel.NotificationException;
import com.incidentplatform.notification.channel.NotificationFailureReason;
import com.incidentplatform.notification.channel.SlackNotificationChannel;
import com.incidentplatform.notification.client.IncidentAckClient;
import com.incidentplatform.notification.client.OncallClient;
import com.incidentplatform.notification.client.SlackWorkspaceClient;
import com.incidentplatform.notification.client.SlackWorkspaceLookupUnavailableException;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.security.TenantAccess;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
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
    @Mock private SlackWorkspaceClient slackWorkspaceClient;
    @Mock private AuditEventPublisher auditEventPublisher;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private SlackActionService service;

    /** What auth-service says of a tenant (backlog #0-82); every other tenant has full access. */
    private final Map<String, TenantAccess> knownAccess = new HashMap<>();

    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final String TENANT_ID = "test-tenant";
    private static final String CHANNEL = "#incidents";
    private static final String MESSAGE_TS = "1234567890.123456";
    private static final String BOT_TOKEN = "xoxb-tenant-own-token";

    @BeforeEach
    void setUp() {
        service = new SlackActionService(
                incidentAckClient, slackChannel, messageStore,
                oncallClient, slackWorkspaceClient, new ObjectMapper(), auditEventPublisher,
                tenantId -> knownAccess.getOrDefault(tenantId, TenantAccess.FULL), meterRegistry);
    }

    private void givenTenantWorkspace() {
        given(slackWorkspaceClient.getWorkspace(TENANT_ID)).willReturn(Optional.of(
                new SlackWorkspaceClient.SlackWorkspaceInfo(BOT_TOKEN, null, false, null)));
    }

    @Nested
    @DisplayName("all channels succeed")
    class AllSucceed {

        @Test
        @DisplayName("removes the tracking row for each successfully-updated " +
                "channel and does not publish an audit event")
        void removesTrackingRowsAndPublishesNothing() {
            givenTenantWorkspace();
            given(messageStore.findAllChannelsForIncident(INCIDENT_ID))
                    .willReturn(List.of(CHANNEL));

            service.updateSlackMessages(
                    INCIDENT_ID, TENANT_ID, CHANNEL, MESSAGE_TS, "Jane Doe");

            then(slackChannel).should().updateMessageAfterAck(
                    eq(CHANNEL), eq(MESSAGE_TS), eq("Jane Doe"), any(NotificationRequest.class), eq(BOT_TOKEN));
            then(messageStore).should().remove(INCIDENT_ID, CHANNEL);
            then(messageStore).should(never()).removeAllForIncident(any());
            then(auditEventPublisher).should(never())
                    .publishIncident(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("updates every tracked channel, not just the one the " +
                "button was clicked in")
        void updatesEveryTrackedChannel() {
            givenTenantWorkspace();
            final String secondChannel = "#oncall-team-a";
            given(messageStore.findAllChannelsForIncident(INCIDENT_ID))
                    .willReturn(List.of(CHANNEL, secondChannel));
            given(messageStore.find(INCIDENT_ID, secondChannel))
                    .willReturn(Optional.of("9999.111111"));

            service.updateSlackMessages(
                    INCIDENT_ID, TENANT_ID, CHANNEL, MESSAGE_TS, "Jane Doe");

            then(slackChannel).should().updateMessageAfterAck(
                    eq(CHANNEL), eq(MESSAGE_TS), anyString(), any(), anyString());
            then(slackChannel).should().updateMessageAfterAck(
                    eq(secondChannel), eq("9999.111111"), anyString(), any(), anyString());
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
            givenTenantWorkspace();
            final String secondChannel = "#oncall-team-a";
            given(messageStore.findAllChannelsForIncident(INCIDENT_ID))
                    .willReturn(List.of(CHANNEL, secondChannel));
            given(messageStore.find(INCIDENT_ID, secondChannel))
                    .willReturn(Optional.of("9999.111111"));

            // The primary channel (button-click channel) fails; the second succeeds.
            willThrow(new NotificationException("SLACK", CHANNEL,
                    NotificationFailureReason.SLACK_UNAVAILABLE, null))
                    .given(slackChannel).updateMessageAfterAck(
                            eq(CHANNEL), eq(MESSAGE_TS), anyString(), any(), anyString());

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
            givenTenantWorkspace();
            final String secondChannel = "#oncall-team-a";
            given(messageStore.findAllChannelsForIncident(INCIDENT_ID))
                    .willReturn(List.of(CHANNEL, secondChannel));
            given(messageStore.find(INCIDENT_ID, secondChannel))
                    .willReturn(Optional.of("9999.111111"));

            willThrow(new NotificationException("SLACK", CHANNEL,
                    NotificationFailureReason.SLACK_UNAVAILABLE, null))
                    .given(slackChannel).updateMessageAfterAck(
                            eq(CHANNEL), eq(MESSAGE_TS), anyString(), any(), anyString());

            service.updateSlackMessages(
                    INCIDENT_ID, TENANT_ID, CHANNEL, MESSAGE_TS, "Jane Doe");

            // then — the second channel was still attempted despite the first's failure
            then(slackChannel).should().updateMessageAfterAck(
                    eq(secondChannel), eq("9999.111111"), anyString(), any(), anyString());
        }

        /**
         * Backlog #0-93 (review): the channel's fallback rethrows an exception
         * that is not the HTTP client's; it must still fail only its channel.
         */
        @Test
        @DisplayName("an unexpected exception from one channel's update fails that channel only; the others are "
                + "still updated and its row kept")
        void unexpectedExceptionFailsOneChannelOnly() {
            givenTenantWorkspace();
            final String secondChannel = "#oncall-team-a";
            given(messageStore.findAllChannelsForIncident(INCIDENT_ID))
                    .willReturn(List.of(CHANNEL, secondChannel));
            given(messageStore.find(INCIDENT_ID, secondChannel))
                    .willReturn(Optional.of("9999.111111"));
            willThrow(new IllegalStateException("a bug"))
                    .given(slackChannel).updateMessageAfterAck(
                            eq(CHANNEL), eq(MESSAGE_TS), anyString(), any(), anyString());

            service.updateSlackMessages(
                    INCIDENT_ID, TENANT_ID, CHANNEL, MESSAGE_TS, "Jane Doe");

            then(slackChannel).should().updateMessageAfterAck(
                    eq(secondChannel), eq("9999.111111"), anyString(), any(), anyString());
            then(messageStore).should(never()).remove(INCIDENT_ID, CHANNEL);
            then(messageStore).should().remove(INCIDENT_ID, secondChannel);
        }

        @Test
        @DisplayName("publishes SLACK_ACK_MESSAGE_UPDATE_FAILED naming the failed channel")
        void publishesAuditEventNamingFailedChannel() {
            givenTenantWorkspace();
            given(messageStore.findAllChannelsForIncident(INCIDENT_ID))
                    .willReturn(List.of(CHANNEL));
            willThrow(new NotificationException("SLACK", CHANNEL,
                    NotificationFailureReason.SLACK_UNAVAILABLE, null))
                    .given(slackChannel).updateMessageAfterAck(
                            eq(CHANNEL), eq(MESSAGE_TS), anyString(), any(), anyString());

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

        /**
         * Backlog #0-84: the event is written after the acknowledgement and
         * the failed updates, so a failure to write it is counted and logged,
         * not thrown to the async caller's generic catch.
         */
        @Test
        @DisplayName("a failure to record SLACK_ACK_MESSAGE_UPDATE_FAILED is counted, not thrown")
        void auditFailureCounted() {
            given(slackWorkspaceClient.getWorkspace(TENANT_ID)).willReturn(Optional.empty());
            given(messageStore.findAllChannelsForIncident(INCIDENT_ID)).willReturn(List.of(CHANNEL));
            willThrow(new IllegalStateException("outbox write failed")).given(auditEventPublisher)
                    .publishIncident(any(), any(), any(), any(), any(), any());

            service.updateSlackMessages(INCIDENT_ID, TENANT_ID, CHANNEL, MESSAGE_TS, "Jane Doe");

            assertThat(meterRegistry.counter("audit.event.unrecorded",
                    "event_type", AuditEventTypes.SLACK_ACK_MESSAGE_UPDATE_FAILED).count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("does not publish an audit event at all when every channel succeeds " +
                "in a multi-channel batch")
        void doesNotPublishWhenAllChannelsSucceedInBatch() {
            givenTenantWorkspace();
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

    /**
     * Backlog #0-21: the ACK update must use the acknowledging tenant's own bot
     * token — resolved from the tenantId carried through the button's value —
     * not one platform-wide token.
     */
    @Nested
    @DisplayName("tenant's own bot token (backlog #0-21)")
    class TenantBotToken {

        @Test
        @DisplayName("resolves the workspace for the ACK's tenant and passes its token to every update")
        void passesTenantTokenToEveryUpdate() {
            givenTenantWorkspace();
            final String secondChannel = "#oncall-team-a";
            given(messageStore.findAllChannelsForIncident(INCIDENT_ID))
                    .willReturn(List.of(CHANNEL, secondChannel));
            given(messageStore.find(INCIDENT_ID, secondChannel))
                    .willReturn(Optional.of("9999.111111"));

            service.updateSlackMessages(
                    INCIDENT_ID, TENANT_ID, CHANNEL, MESSAGE_TS, "Jane Doe");

            then(slackWorkspaceClient).should().getWorkspace(TENANT_ID);
            then(slackChannel).should().updateMessageAfterAck(
                    eq(CHANNEL), eq(MESSAGE_TS), anyString(), any(), eq(BOT_TOKEN));
            then(slackChannel).should().updateMessageAfterAck(
                    eq(secondChannel), eq("9999.111111"), anyString(), any(), eq(BOT_TOKEN));
        }

        @Test
        @DisplayName("no workspace any more (revoked after posting): nothing is updated, every channel reported failed")
        void noWorkspaceFailsEveryChannel() {
            given(slackWorkspaceClient.getWorkspace(TENANT_ID)).willReturn(Optional.empty());
            given(messageStore.findAllChannelsForIncident(INCIDENT_ID))
                    .willReturn(List.of(CHANNEL, "#oncall-team-a"));

            service.updateSlackMessages(
                    INCIDENT_ID, TENANT_ID, CHANNEL, MESSAGE_TS, "Jane Doe");

            then(slackChannel).shouldHaveNoInteractions();
            then(messageStore).should(never()).remove(any(), anyString());
            assertThat(failedChannelsInAuditEvent())
                    .containsExactlyInAnyOrder(CHANNEL, "#oncall-team-a");
        }

        @Test
        @DisplayName("auth-service unavailable: does not throw, every channel reported failed")
        void lookupFailureFailsEveryChannel() {
            given(slackWorkspaceClient.getWorkspace(TENANT_ID))
                    .willThrow(new SlackWorkspaceLookupUnavailableException("down", new RuntimeException()));
            given(messageStore.findAllChannelsForIncident(INCIDENT_ID))
                    .willReturn(List.of(CHANNEL));

            service.updateSlackMessages(
                    INCIDENT_ID, TENANT_ID, CHANNEL, MESSAGE_TS, "Jane Doe");

            then(slackChannel).shouldHaveNoInteractions();
            assertThat(failedChannelsInAuditEvent()).containsExactly(CHANNEL);
        }

        @SuppressWarnings("unchecked")
        private List<String> failedChannelsInAuditEvent() {
            final ArgumentCaptor<Map<String, Object>> metadataCaptor =
                    ArgumentCaptor.forClass(Map.class);
            then(auditEventPublisher).should().publishIncident(
                    eq(INCIDENT_ID), eq(TENANT_ID),
                    eq(AuditEventTypes.SLACK_ACK_MESSAGE_UPDATE_FAILED),
                    anyString(), anyString(), metadataCaptor.capture());
            return (List<String>) metadataCaptor.getValue().get("failedChannels");
        }
    }

    /**
     * Backlog #0-82, step 2b: the ACK button reaches incident-service with this
     * service's token, which the status filter lets through, so the tenant's
     * status is checked here.
     */
    @Nested
    @DisplayName("acknowledge button and tenant suspension (backlog #0-82)")
    class AcknowledgeAndSuspension {

        private String ackPayload(String tenantId) {
            return """
                    {"type":"block_actions","user":{"id":"U123","name":"jan"},
                     "actions":[{"action_id":"acknowledge_incident","value":"%s|%s"}],
                     "container":{"channel_id":"C1","message_ts":"%s"}}
                    """.formatted(INCIDENT_ID, tenantId, MESSAGE_TS);
        }

        @Test
        @DisplayName("a suspended tenant's acknowledgement is refused in either mode: no lookup, no ACK")
        void suspendedRefused() {
            knownAccess.put(TENANT_ID, TenantAccess.READ_ONLY);
            service.processAction(ackPayload(TENANT_ID));
            knownAccess.put(TENANT_ID, TenantAccess.NONE);
            service.processAction(ackPayload(TENANT_ID));

            then(oncallClient).shouldHaveNoInteractions();
            then(incidentAckClient).shouldHaveNoInteractions();
            assertThat(meterRegistry.get("slack.ack.refused").tag("reason", "tenant_suspended").counter().count())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("an active tenant's acknowledgement goes to incident-service")
        void activeAcknowledged() {
            given(oncallClient.findBySlackUserId(TENANT_ID, "U123")).willReturn(Optional.empty());
            given(incidentAckClient.acknowledgeIncident(eq(INCIDENT_ID), eq(TENANT_ID), any())).willReturn(false);

            service.processAction(ackPayload(TENANT_ID));

            then(incidentAckClient).should().acknowledgeIncident(eq(INCIDENT_ID), eq(TENANT_ID), any());
            assertThat(meterRegistry.get("slack.ack.refused").counter().count()).as("registered at zero").isZero();
        }

        @Test
        @DisplayName("a tenant id that is not one is ignored before anything is asked")
        void invalidTenantIgnored() {
            service.processAction(ackPayload("Not A Tenant"));

            then(oncallClient).shouldHaveNoInteractions();
            then(incidentAckClient).shouldHaveNoInteractions();
        }
    }
}
