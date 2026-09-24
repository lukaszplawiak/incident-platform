package com.incidentplatform.notification.router;

import com.incidentplatform.notification.channel.NotificationChannel;
import com.incidentplatform.notification.client.OncallClient;
import com.incidentplatform.notification.client.OncallLookupUnavailableException;
import com.incidentplatform.notification.client.SlackWorkspaceClient;
import com.incidentplatform.notification.client.SlackWorkspaceLookupUnavailableException;
import com.incidentplatform.notification.domain.UndeliverableReason;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.shared.domain.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.incidentplatform.notification.router.NotificationChannels.EMAIL;
import static com.incidentplatform.notification.router.NotificationChannels.SLACK;
import static com.incidentplatform.notification.router.NotificationChannels.SMS;
import static com.incidentplatform.notification.router.NotificationEventTypes.INCIDENT_ACKNOWLEDGED;
import static com.incidentplatform.notification.router.NotificationEventTypes.INCIDENT_CLOSED;
import static com.incidentplatform.notification.router.NotificationEventTypes.INCIDENT_ESCALATED;
import static com.incidentplatform.notification.router.NotificationEventTypes.INCIDENT_OPENED;
import static com.incidentplatform.notification.router.NotificationEventTypes.INCIDENT_RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("NotificationRouter")
class NotificationRouterTest {

    private NotificationRouter router;

    private FakeChannel emailChannel;
    private FakeChannel slackChannel;
    private FakeChannel smsChannel;

    // Backlog #0-21: every tenant in these tests has an active Slack workspace
    // unless a test says otherwise, so the pre-#0-21 routing tests keep
    // asserting exactly what they did before.
    private SlackWorkspaceClient slackWorkspaceClient;
    private static final SlackWorkspaceClient.SlackWorkspaceInfo WORKSPACE =
            new SlackWorkspaceClient.SlackWorkspaceInfo("xoxb-test", null, false, null);

    private static final String TENANT_ID = "test-tenant";
    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final OncallClient.OncallInfo PRIMARY_CONTACT =
            new OncallClient.OncallInfo("primary-user", "Pat Primary", "primary@acme.com",
                    "+48111111111", "UPRIMARY", "PRIMARY");

    @BeforeEach
    void setUp() {
        emailChannel = new FakeChannel(EMAIL);
        slackChannel = new FakeChannel(SLACK);
        smsChannel   = new FakeChannel(SMS);

        final OncallClient oncallClient = mock(OncallClient.class);
        when(oncallClient.getCurrentOncall(anyString(), any(), anyString()))
                .thenReturn(Optional.of(PRIMARY_CONTACT));

        slackWorkspaceClient = mock(SlackWorkspaceClient.class);
        when(slackWorkspaceClient.getWorkspace(TENANT_ID)).thenReturn(Optional.of(WORKSPACE));

        router = new NotificationRouter(
                List.of(emailChannel, slackChannel, smsChannel),
                oncallClient, slackWorkspaceClient);
    }

    @Nested
    @DisplayName("IncidentOpenedEvent routing")
    class IncidentOpened {

        @Test
        @DisplayName("should route to EMAIL and SLACK only")
        void shouldRouteToEmailAndSlack() {
            // when
            final var result = router.route(
                    INCIDENT_OPENED, INCIDENT_ID,
                    TENANT_ID, Severity.CRITICAL, "High CPU", null, null).requests();

            // then
            final var channelNames = result.stream()
                    .map(cr -> cr.channel().channelName())
                    .toList();

            assertThat(channelNames)
                    .containsExactlyInAnyOrder(EMAIL, SLACK);
            assertThat(channelNames).doesNotContain(SMS);
        }

        @Test
        @DisplayName("should build subject with severity and title")
        void shouldBuildCorrectSubject() {
            // when
            final var result = router.route(
                    INCIDENT_OPENED, INCIDENT_ID,
                    TENANT_ID, Severity.CRITICAL, "High CPU Usage", null, null).requests();

            // then
            result.forEach(cr -> {
                assertThat(cr.request().subject())
                        .contains("CRITICAL")
                        .contains("High CPU Usage");
            });
        }

        @Test
        @DisplayName("should set tenantId and incidentId in request")
        void shouldSetTenantAndIncidentId() {
            // when
            final var result = router.route(
                    INCIDENT_OPENED, INCIDENT_ID,
                    TENANT_ID, Severity.HIGH, "Test Incident", null, null).requests();

            // then
            result.forEach(cr -> {
                assertThat(cr.request().tenantId()).isEqualTo(TENANT_ID);
                assertThat(cr.request().incidentId()).isEqualTo(INCIDENT_ID);
                assertThat(cr.request().eventType()).isEqualTo(INCIDENT_OPENED);
            });
        }
    }

    @Nested
    @DisplayName("IncidentEscalatedEvent routing")
    class IncidentEscalated {

        @Test
        @DisplayName("should route to EMAIL, SLACK and SMS")
        void shouldRouteToAllChannels() {
            // when
            final var result = router.route(
                    INCIDENT_ESCALATED, INCIDENT_ID,
                    TENANT_ID, Severity.CRITICAL, "Database Down", null, null).requests();

            // then
            final var channelNames = result.stream()
                    .map(cr -> cr.channel().channelName())
                    .toList();

            assertThat(channelNames)
                    .containsExactlyInAnyOrder(EMAIL, SLACK, SMS);
        }

        @Test
        @DisplayName("should include ESCALATED in subject")
        void shouldIncludeEscalatedInSubject() {
            // when
            final var result = router.route(
                    INCIDENT_ESCALATED, INCIDENT_ID,
                    TENANT_ID, Severity.CRITICAL, "Database Down", null, null).requests();

            // then
            result.forEach(cr ->
                    assertThat(cr.request().subject())
                            .containsIgnoringCase("ESCALATED"));
        }
    }

    @Nested
    @DisplayName("IncidentResolvedEvent routing")
    class IncidentResolved {

        @Test
        @DisplayName("should route to EMAIL and SLACK only")
        void shouldRouteToEmailAndSlack() {
            // when
            final var result = router.route(
                    INCIDENT_RESOLVED, INCIDENT_ID,
                    TENANT_ID, Severity.HIGH, "API Outage", null, null).requests();

            // then
            final var channelNames = result.stream()
                    .map(cr -> cr.channel().channelName())
                    .toList();

            assertThat(channelNames)
                    .containsExactlyInAnyOrder(EMAIL, SLACK);
        }
    }

    @Nested
    @DisplayName("IncidentAcknowledgedEvent routing")
    class IncidentAcknowledged {

        @Test
        @DisplayName("should route to SLACK only")
        void shouldRouteToSlackOnly() {
            // when
            final var result = router.route(
                    INCIDENT_ACKNOWLEDGED, INCIDENT_ID,
                    TENANT_ID, Severity.MEDIUM, "Memory Leak", null, null).requests();

            // then
            final var channelNames = result.stream()
                    .map(cr -> cr.channel().channelName())
                    .toList();

            assertThat(channelNames).containsExactly(SLACK);
        }
    }

    @Nested
    @DisplayName("IncidentClosedEvent routing")
    class IncidentClosed {

        @Test
        @DisplayName("should route to EMAIL only")
        void shouldRouteToEmailOnly() {
            // when
            final var result = router.route(
                    INCIDENT_CLOSED, INCIDENT_ID,
                    TENANT_ID, Severity.LOW, "Disk Space", null, null).requests();

            // then
            final var channelNames = result.stream()
                    .map(cr -> cr.channel().channelName())
                    .toList();

            assertThat(channelNames).containsExactly(EMAIL);
        }
    }

    @Nested
    @DisplayName("Unknown event type")
    class UnknownEvent {

        @Test
        @DisplayName("should return empty list for unknown event type")
        void shouldReturnEmptyForUnknownEvent() {
            // when
            final var result = router.route(
                    "UnknownEvent", INCIDENT_ID,
                    TENANT_ID, Severity.HIGH, "Test", null, null).requests();

            // then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Disabled channels")
    class DisabledChannels {

        @Test
        @DisplayName("should skip disabled channels")
        void shouldSkipDisabledChannels() {
            // given — SMS disabled
            final FakeChannel disabledSms = new FakeChannel(SMS, false);

            final OncallClient oncallClient = mock(OncallClient.class);
            when(oncallClient.getCurrentOncall(anyString(), any(), anyString()))
                    .thenReturn(Optional.of(PRIMARY_CONTACT));

            final NotificationRouter routerWithDisabledSms =
                    new NotificationRouter(
                            List.of(emailChannel, slackChannel, disabledSms),
                            oncallClient, slackWorkspaceClient);

            // when
            final var result = routerWithDisabledSms.route(
                    INCIDENT_ESCALATED, INCIDENT_ID,
                    TENANT_ID, Severity.CRITICAL, "Critical Incident", null, null).requests();

            // then
            final var channelNames = result.stream()
                    .map(cr -> cr.channel().channelName())
                    .toList();

            assertThat(channelNames).doesNotContain(SMS);
            assertThat(channelNames).containsExactlyInAnyOrder(EMAIL, SLACK);
        }
    }


    /**
     * Backlog #0-18: tenant content only reaches members of the tenant. With no
     * on-call contact there is no fallback address: the result is undeliverable
     * for every event type, and a channel the contact has no address for is
     * skipped.
     */
    @Nested
    @DisplayName("no shared destination for tenant content (backlog #0-18)")
    class Undeliverable {

        private OncallClient oncallClient;
        private NotificationRouter isolatedRouter;

        @BeforeEach
        void setUpRouter() {
            oncallClient = mock(OncallClient.class);
            isolatedRouter = new NotificationRouter(
                    List.of(emailChannel, slackChannel, smsChannel), oncallClient,
                    slackWorkspaceClient);
        }

        @ParameterizedTest
        @ValueSource(strings = {INCIDENT_OPENED, INCIDENT_ESCALATED, INCIDENT_ACKNOWLEDGED,
                INCIDENT_RESOLVED, INCIDENT_CLOSED})
        @DisplayName("nobody on call: undeliverable and nothing to send, for every event type")
        void undeliverableForEveryEventType(String eventType) {
            when(oncallClient.getCurrentOncall(anyString(), any(), anyString()))
                    .thenReturn(Optional.empty());
            when(oncallClient.findCurrentByUserId(anyString(), anyString()))
                    .thenReturn(Optional.empty());

            final var routing = isolatedRouter.route(eventType, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "Secret Customer Hostname Down", UUID.randomUUID(), null);

            assertThat(routing.isUndeliverable()).isTrue();
            assertThat(routing.undeliverableReason()).isEqualTo(UndeliverableReason.NO_ONCALL);
            assertThat(routing.requests()).isEmpty();
        }

        @Test
        @DisplayName("a contact with no address on any enabled channel is undeliverable (NO_REACHABLE_CHANNEL)")
        void contactWithoutAnyAddress() {
            when(oncallClient.getCurrentOncall(anyString(), any(), anyString()))
                    .thenReturn(Optional.of(new OncallClient.OncallInfo(
                            "u", "No Contact", null, null, null, "PRIMARY")));

            final var routing = isolatedRouter.route(INCIDENT_OPENED, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU", null, null);

            assertThat(routing.isUndeliverable()).isTrue();
            assertThat(routing.undeliverableReason())
                    .isEqualTo(UndeliverableReason.NO_REACHABLE_CHANNEL);
        }

        @Test
        @DisplayName("a contact with only an email is sent to on the email channel alone")
        void contactWithOnlyEmail() {
            when(oncallClient.getCurrentOncall(anyString(), any(), anyString()))
                    .thenReturn(Optional.of(new OncallClient.OncallInfo(
                            "u", "Email Only", "only@acme.com", null, null, "PRIMARY")));

            final var routing = isolatedRouter.route(INCIDENT_OPENED, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU", null, null);

            assertThat(routing.isUndeliverable()).isFalse();
            assertThat(routing.requests()).extracting(cr -> cr.channel().channelName())
                    .containsExactly(EMAIL);
            assertThat(routing.requests().get(0).request().recipient()).isEqualTo("only@acme.com");
        }

        @Test
        @DisplayName("a Slack id the channel would silently ignore is no address: the channel is skipped and reported")
        void invalidSlackIdIsSkipped() {
            when(oncallClient.getCurrentOncall(anyString(), any(), anyString()))
                    .thenReturn(Optional.of(new OncallClient.OncallInfo(
                            "u", "Grid User", "grid@acme.com", null, "W0123456789", "PRIMARY")));

            final var routing = isolatedRouter.route(INCIDENT_OPENED, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU", null, null);

            assertThat(routing.requests()).extracting(cr -> cr.channel().channelName())
                    .containsExactly(EMAIL);
            assertThat(routing.skippedChannels()).containsExactly(SLACK);
        }

        @Test
        @DisplayName("an event whose only channel is Slack is undeliverable when the Slack id is invalid, not 'sent'")
        void slackOnlyEventWithInvalidSlackId() {
            when(oncallClient.getCurrentOncall(anyString(), any(), anyString()))
                    .thenReturn(Optional.of(new OncallClient.OncallInfo(
                            "u", "Handle User", "handle@acme.com", "+48111111111",
                            "@some-handle", "PRIMARY")));

            final var routing = isolatedRouter.route(INCIDENT_ACKNOWLEDGED, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU", null, null);

            assertThat(routing.isUndeliverable()).isTrue();
            assertThat(routing.undeliverableReason())
                    .isEqualTo(UndeliverableReason.NO_REACHABLE_CHANNEL);
            assertThat(routing.skippedChannels()).containsExactly(SLACK);
        }

        @Test
        @DisplayName("every channel the contact has no address for is reported as skipped")
        void skippedChannelsAreReported() {
            when(oncallClient.getCurrentOncall(anyString(), any(), anyString()))
                    .thenReturn(Optional.of(new OncallClient.OncallInfo(
                            "u", "Email Only", "only@acme.com", null, null, "PRIMARY")));

            final var routing = isolatedRouter.route(INCIDENT_ESCALATED, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU", null, null);

            assertThat(routing.requests()).extracting(cr -> cr.channel().channelName())
                    .containsExactly(EMAIL);
            assertThat(routing.skippedChannels()).containsExactlyInAnyOrder(SLACK, SMS);
        }

        @Test
        @DisplayName("nothing to send — not undeliverable — when every channel for the event is disabled")
        void allChannelsDisabled() {
            final NotificationRouter disabledRouter = new NotificationRouter(
                    List.of(new FakeChannel(EMAIL, false), new FakeChannel(SLACK, false),
                            new FakeChannel(SMS, false)), oncallClient, slackWorkspaceClient);

            final var routing = disabledRouter.route(INCIDENT_OPENED, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU", null, null);

            assertThat(routing.isUndeliverable()).isFalse();
            assertThat(routing.requests()).isEmpty();
            then(oncallClient).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("an oncall-service failure propagates — it is not read as 'nobody on call' (backlog #0-19)")
        void lookupFailurePropagates() {
            when(oncallClient.getCurrentOncall(anyString(), any(), anyString()))
                    .thenThrow(new OncallLookupUnavailableException("down", new RuntimeException()));

            assertThatThrownBy(() -> isolatedRouter.route(INCIDENT_OPENED, INCIDENT_ID,
                    TENANT_ID, Severity.CRITICAL, "High CPU", null, null))
                    .isInstanceOf(OncallLookupUnavailableException.class);
        }
    }

    /**
     * Backlog #0-21: Slack needs the tenant's own workspace. "Not installed" is a
     * normal skip; "auth-service unavailable" skips only Slack while anything else
     * can go out, and propagates only when Slack was the sole way to reach the
     * contact — see {@code SlackWorkspaceLookupUnavailableException}.
     */
    @Nested
    @DisplayName("tenant's Slack workspace (backlog #0-21)")
    class SlackWorkspace {

        private OncallClient oncallClient;
        private NotificationRouter workspaceRouter;

        @BeforeEach
        void setUpRouter() {
            oncallClient = mock(OncallClient.class);
            when(oncallClient.getCurrentOncall(anyString(), any(), anyString()))
                    .thenReturn(Optional.of(PRIMARY_CONTACT));
            workspaceRouter = new NotificationRouter(
                    List.of(emailChannel, slackChannel, smsChannel), oncallClient,
                    slackWorkspaceClient);
        }

        @Test
        @DisplayName("no Slack workspace for the tenant: Slack is skipped and reported, email still goes out")
        void noWorkspaceSkipsSlack() {
            when(slackWorkspaceClient.getWorkspace(TENANT_ID)).thenReturn(Optional.empty());

            final var routing = workspaceRouter.route(INCIDENT_OPENED, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU", null, null);

            assertThat(routing.isUndeliverable()).isFalse();
            assertThat(routing.requests()).extracting(cr -> cr.channel().channelName())
                    .containsExactly(EMAIL);
            assertThat(routing.skippedChannels()).containsExactly(SLACK);
        }

        @Test
        @DisplayName("no Slack workspace on a Slack-only event: undeliverable (NO_REACHABLE_CHANNEL) — a real answer, not an outage")
        void noWorkspaceOnSlackOnlyEvent() {
            when(slackWorkspaceClient.getWorkspace(TENANT_ID)).thenReturn(Optional.empty());

            final var routing = workspaceRouter.route(INCIDENT_ACKNOWLEDGED, INCIDENT_ID,
                    TENANT_ID, Severity.CRITICAL, "High CPU", null, null);

            assertThat(routing.isUndeliverable()).isTrue();
            assertThat(routing.undeliverableReason())
                    .isEqualTo(UndeliverableReason.NO_REACHABLE_CHANNEL);
        }

        @Test
        @DisplayName("auth-service unavailable: only Slack is skipped, email goes out, route() does not throw")
        void lookupFailureSkipsOnlySlack() {
            when(slackWorkspaceClient.getWorkspace(TENANT_ID))
                    .thenThrow(new SlackWorkspaceLookupUnavailableException("down", new RuntimeException()));

            final var routing = workspaceRouter.route(INCIDENT_ESCALATED, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU", null, null);

            assertThat(routing.isUndeliverable()).isFalse();
            assertThat(routing.requests()).extracting(cr -> cr.channel().channelName())
                    .containsExactlyInAnyOrder(EMAIL, SMS);
            assertThat(routing.skippedChannels()).containsExactly(SLACK);
        }

        @Test
        @DisplayName("auth-service unavailable on a Slack-only event: the failure propagates — not read as undeliverable")
        void lookupFailurePropagatesWhenSlackIsTheOnlyChannel() {
            when(slackWorkspaceClient.getWorkspace(TENANT_ID))
                    .thenThrow(new SlackWorkspaceLookupUnavailableException("down", new RuntimeException()));

            assertThatThrownBy(() -> workspaceRouter.route(INCIDENT_ACKNOWLEDGED, INCIDENT_ID,
                    TENANT_ID, Severity.CRITICAL, "High CPU", null, null))
                    .isInstanceOf(SlackWorkspaceLookupUnavailableException.class);
        }

        @Test
        @DisplayName("a contact without a usable Slack id never costs an auth-service lookup")
        void noLookupWithoutSlackId() {
            when(oncallClient.getCurrentOncall(anyString(), any(), anyString()))
                    .thenReturn(Optional.of(new OncallClient.OncallInfo(
                            "u", "Email Only", "only@acme.com", null, null, "PRIMARY")));

            workspaceRouter.route(INCIDENT_OPENED, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU", null, null);

            then(slackWorkspaceClient).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("the workspace is looked up for the notification's own tenant")
        void lookupIsTenantScoped() {
            workspaceRouter.route(INCIDENT_OPENED, INCIDENT_ID, TENANT_ID,
                    Severity.CRITICAL, "High CPU", null, null);

            then(slackWorkspaceClient).should().getWorkspace(TENANT_ID);
        }
    }

    /**
     * Backlog #0-1: an escalation is sent to the user in {@code escalateTo},
     * not to the PRIMARY on-call. These tests assert the actual recipients
     * (the tests above only look at channels), and that the lookup is by
     * tenant and user id together.
     */
    @Nested
    @DisplayName("IncidentEscalatedEvent recipient — escalation target (backlog #0-1)")
    class EscalationTarget {

        private final UUID escalateTo = UUID.randomUUID();
        private OncallClient oncallClient;
        private NotificationRouter escalationRouter;

        @BeforeEach
        void setUpRouter() {
            oncallClient = mock(OncallClient.class);
            when(oncallClient.getCurrentOncall(anyString(), any(), anyString()))
                    .thenReturn(Optional.of(new OncallClient.OncallInfo(
                            "primary-user", "Pat Primary", "primary@acme.com",
                            "+48111111111", "UPRIMARY", "PRIMARY")));
            escalationRouter = new NotificationRouter(
                    List.of(emailChannel, slackChannel, smsChannel),
                    oncallClient, slackWorkspaceClient);
        }

        private String recipientOn(List<NotificationRouter.ChannelRequest> result,
                                   String channelName) {
            return result.stream()
                    .filter(cr -> cr.channel().channelName().equals(channelName))
                    .findFirst().orElseThrow()
                    .request().recipient();
        }

        @Test
        @DisplayName("notifies the escalation target's own email, Slack id and phone — not the PRIMARY")
        void notifiesTheTarget() {
            when(oncallClient.findCurrentByUserId(TENANT_ID, escalateTo.toString()))
                    .thenReturn(Optional.of(new OncallClient.OncallInfo(
                            escalateTo.toString(), "Sam Secondary", "sam@acme.com",
                            "+48222222222", "USECONDARY", "SECONDARY")));

            final var result = escalationRouter.route(INCIDENT_ESCALATED, INCIDENT_ID,
                    TENANT_ID, Severity.CRITICAL, "Database Down", escalateTo, null).requests();

            assertThat(recipientOn(result, EMAIL)).isEqualTo("sam@acme.com");
            assertThat(recipientOn(result, SLACK)).isEqualTo("USECONDARY");
            assertThat(recipientOn(result, SMS)).isEqualTo("+48222222222");
            then(oncallClient).should(never()).getCurrentOncall(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("looks the target up by the request's tenant together with the user id")
        void looksUpByTenantAndUser() {
            when(oncallClient.findCurrentByUserId(anyString(), anyString()))
                    .thenReturn(Optional.empty());

            escalationRouter.route(INCIDENT_ESCALATED, INCIDENT_ID,
                    TENANT_ID, Severity.CRITICAL, "Database Down", escalateTo, null);

            then(oncallClient).should().findCurrentByUserId(TENANT_ID, escalateTo.toString());
        }

        @Test
        @DisplayName("skips a channel the target has no address for — it is never replaced by a shared one")
        void skipsChannelWithoutAddress() {
            // target has an email but no phone and no Slack id
            when(oncallClient.findCurrentByUserId(TENANT_ID, escalateTo.toString()))
                    .thenReturn(Optional.of(new OncallClient.OncallInfo(
                            escalateTo.toString(), "Sam Secondary", "sam@acme.com",
                            null, null, "SECONDARY")));

            final var result = escalationRouter.route(INCIDENT_ESCALATED, INCIDENT_ID,
                    TENANT_ID, Severity.CRITICAL, "Database Down", escalateTo, null).requests();

            assertThat(result).extracting(cr -> cr.channel().channelName())
                    .containsExactly(EMAIL);
            assertThat(recipientOn(result, EMAIL)).isEqualTo("sam@acme.com");
        }

        @Test
        @DisplayName("falls back to the tenant's PRIMARY — as before this change — when the target is not found")
        void usesPrimaryWhenTargetNotFound() {
            when(oncallClient.findCurrentByUserId(TENANT_ID, escalateTo.toString()))
                    .thenReturn(Optional.empty());

            final var result = escalationRouter.route(INCIDENT_ESCALATED, INCIDENT_ID,
                    TENANT_ID, Severity.CRITICAL, "Database Down", escalateTo, null).requests();

            assertThat(recipientOn(result, EMAIL)).isEqualTo("primary@acme.com");
            assertThat(recipientOn(result, SLACK)).isEqualTo("UPRIMARY");
            assertThat(recipientOn(result, SMS)).isEqualTo("+48111111111");
            then(oncallClient).should().getCurrentOncall(TENANT_ID, null, "PRIMARY");
        }

        @Test
        @DisplayName("falls back to the tenant's PRIMARY when the escalation has no target, without a by-user lookup")
        void usesPrimaryWhenNoTarget() {
            final var result = escalationRouter.route(INCIDENT_ESCALATED, INCIDENT_ID,
                    TENANT_ID, Severity.CRITICAL, "Database Down", null, null).requests();

            assertThat(recipientOn(result, EMAIL)).isEqualTo("primary@acme.com");
            then(oncallClient).should(never()).findCurrentByUserId(anyString(), anyString());
            then(oncallClient).should().getCurrentOncall(TENANT_ID, null, "PRIMARY");
        }

        @Test
        @DisplayName("is undeliverable — with no fallback address — when neither the target nor the PRIMARY is found")
        void undeliverableWhenNobodyIsFound() {
            when(oncallClient.findCurrentByUserId(TENANT_ID, escalateTo.toString()))
                    .thenReturn(Optional.empty());
            when(oncallClient.getCurrentOncall(TENANT_ID, null, "PRIMARY"))
                    .thenReturn(Optional.empty());

            final var routing = escalationRouter.route(INCIDENT_ESCALATED, INCIDENT_ID,
                    TENANT_ID, Severity.CRITICAL, "Database Down", escalateTo, null);

            assertThat(routing.isUndeliverable()).isTrue();
            assertThat(routing.undeliverableReason()).isEqualTo(UndeliverableReason.NO_ONCALL);
            assertThat(routing.requests()).isEmpty();
        }

        @Test
        @DisplayName("other event types still go to the PRIMARY, even when a target id is passed")
        void otherEventsStillUsePrimary() {
            final var result = escalationRouter.route(INCIDENT_OPENED, INCIDENT_ID,
                    TENANT_ID, Severity.CRITICAL, "Database Down", escalateTo, null).requests();

            assertThat(recipientOn(result, EMAIL)).isEqualTo("primary@acme.com");
            then(oncallClient).should().getCurrentOncall(TENANT_ID, null, "PRIMARY");
            then(oncallClient).should(never()).findCurrentByUserId(anyString(), anyString());
        }
    }

    private static class FakeChannel implements NotificationChannel {

        private final String name;
        private final boolean enabled;

        FakeChannel(String name) {
            this(name, true);
        }

        FakeChannel(String name, boolean enabled) {
            this.name = name;
            this.enabled = enabled;
        }

        @Override
        public String channelName() { return name; }

        @Override
        public boolean isEnabled() { return enabled; }

        @Override
        public void send(NotificationRequest request) {}
    }
}