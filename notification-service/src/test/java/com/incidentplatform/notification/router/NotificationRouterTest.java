package com.incidentplatform.notification.router;

import com.incidentplatform.notification.channel.NotificationChannel;
import com.incidentplatform.notification.dto.NotificationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotificationRouter")
class NotificationRouterTest {

    private NotificationRouter router;

    private FakeChannel emailChannel;
    private FakeChannel slackChannel;
    private FakeChannel smsChannel;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID INCIDENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        emailChannel = new FakeChannel("EMAIL");
        slackChannel = new FakeChannel("SLACK");
        smsChannel   = new FakeChannel("SMS");

        router = new NotificationRouter(
                List.of(emailChannel, slackChannel, smsChannel));
    }

    @Nested
    @DisplayName("IncidentOpenedEvent routing")
    class IncidentOpened {

        @Test
        @DisplayName("should route to EMAIL and SLACK only")
        void shouldRouteToEmailAndSlack() {
            // when
            final var result = router.route(
                    "IncidentOpenedEvent", INCIDENT_ID,
                    TENANT_ID, "CRITICAL", "High CPU");

            // then
            final var channelNames = result.stream()
                    .map(cr -> cr.channel().channelName())
                    .toList();

            assertThat(channelNames)
                    .containsExactlyInAnyOrder("EMAIL", "SLACK");
            assertThat(channelNames).doesNotContain("SMS");
        }

        @Test
        @DisplayName("should build subject with severity and title")
        void shouldBuildCorrectSubject() {
            // when
            final var result = router.route(
                    "IncidentOpenedEvent", INCIDENT_ID,
                    TENANT_ID, "CRITICAL", "High CPU Usage");

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
                    "IncidentOpenedEvent", INCIDENT_ID,
                    TENANT_ID, "HIGH", "Test Incident");

            // then
            result.forEach(cr -> {
                assertThat(cr.request().tenantId()).isEqualTo(TENANT_ID);
                assertThat(cr.request().incidentId()).isEqualTo(INCIDENT_ID);
                assertThat(cr.request().eventType())
                        .isEqualTo("IncidentOpenedEvent");
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
                    "IncidentEscalatedEvent", INCIDENT_ID,
                    TENANT_ID, "CRITICAL", "Database Down");

            // then
            final var channelNames = result.stream()
                    .map(cr -> cr.channel().channelName())
                    .toList();

            assertThat(channelNames)
                    .containsExactlyInAnyOrder("EMAIL", "SLACK", "SMS");
        }

        @Test
        @DisplayName("should include ESCALATED in subject")
        void shouldIncludeEscalatedInSubject() {
            // when
            final var result = router.route(
                    "IncidentEscalatedEvent", INCIDENT_ID,
                    TENANT_ID, "CRITICAL", "Database Down");

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
                    "IncidentResolvedEvent", INCIDENT_ID,
                    TENANT_ID, "HIGH", "API Outage");

            // then
            final var channelNames = result.stream()
                    .map(cr -> cr.channel().channelName())
                    .toList();

            assertThat(channelNames)
                    .containsExactlyInAnyOrder("EMAIL", "SLACK");
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
                    "IncidentAcknowledgedEvent", INCIDENT_ID,
                    TENANT_ID, "MEDIUM", "Memory Leak");

            // then
            final var channelNames = result.stream()
                    .map(cr -> cr.channel().channelName())
                    .toList();

            assertThat(channelNames).containsExactly("SLACK");
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
                    "IncidentClosedEvent", INCIDENT_ID,
                    TENANT_ID, "LOW", "Disk Space");

            // then
            final var channelNames = result.stream()
                    .map(cr -> cr.channel().channelName())
                    .toList();

            assertThat(channelNames).containsExactly("EMAIL");
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
                    TENANT_ID, "HIGH", "Test");

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
            // given — SMS off
            final FakeChannel disabledSms = new FakeChannel("SMS", false);
            final NotificationRouter routerWithDisabledSms =
                    new NotificationRouter(
                            List.of(emailChannel, slackChannel, disabledSms));

            // when
            final var result = routerWithDisabledSms.route(
                    "IncidentEscalatedEvent", INCIDENT_ID,
                    TENANT_ID, "CRITICAL", "Critical Incident");

            // then
            final var channelNames = result.stream()
                    .map(cr -> cr.channel().channelName())
                    .toList();

            assertThat(channelNames).doesNotContain("SMS");
            assertThat(channelNames).containsExactlyInAnyOrder("EMAIL", "SLACK");
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
        public void send(NotificationRequest request) {
        }
    }
}