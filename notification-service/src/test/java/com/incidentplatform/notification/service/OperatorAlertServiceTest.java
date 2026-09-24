package com.incidentplatform.notification.service;

import com.incidentplatform.notification.channel.NotificationChannel;
import com.incidentplatform.notification.channel.NotificationException;
import com.incidentplatform.notification.config.NotificationChannelProperties;
import com.incidentplatform.notification.domain.UndeliverableReason;
import com.incidentplatform.notification.dto.NotificationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Backlog #0-18: the alert to the platform operator carries identifiers and a
 * reason, never the tenant's incident text, and its failure never breaks the
 * caller.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OperatorAlertService")
class OperatorAlertServiceTest {

    @Mock private NotificationChannel emailChannel;

    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final String TENANT_ID = "acme-corp";
    private static final String OPERATOR_EMAIL = "operator@platform.example";

    @BeforeEach
    void setUp() {
        lenient().when(emailChannel.channelName()).thenReturn("EMAIL");
        lenient().when(emailChannel.isEnabled()).thenReturn(true);
    }

    private static NotificationChannelProperties properties(String operatorEmail) {
        return new NotificationChannelProperties(
                new NotificationChannelProperties.Channels(
                        new NotificationChannelProperties.Email(true, "alerts@test.com"),
                        new NotificationChannelProperties.Slack(true, "secret", "http://localhost"),
                        new NotificationChannelProperties.Sms(true, "+1234567890")),
                new NotificationChannelProperties.OperatorAlert(operatorEmail, MIN_INTERVAL));
    }

    private static final Duration MIN_INTERVAL = Duration.ofMinutes(15);

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();

    private OperatorAlertService service(String operatorEmail) {
        return new OperatorAlertService(List.of(emailChannel), properties(operatorEmail), clock);
    }

    @Test
    @DisplayName("emails the operator identifiers and the reason — and a fixed, content-free title")
    void emailsContentFreeAlert() {
        service(OPERATOR_EMAIL).undeliverable(
                INCIDENT_ID, TENANT_ID, "IncidentEscalatedEvent", UndeliverableReason.NO_ONCALL);

        final ArgumentCaptor<NotificationRequest> captor =
                ArgumentCaptor.forClass(NotificationRequest.class);
        then(emailChannel).should().send(captor.capture());
        final NotificationRequest request = captor.getValue();

        assertThat(request.recipient()).isEqualTo(OPERATOR_EMAIL);
        assertThat(request.subject()).contains("NO_ONCALL");
        assertThat(request.message())
                .contains(TENANT_ID)
                .contains(INCIDENT_ID.toString())
                .contains("IncidentEscalatedEvent")
                .contains("NO_ONCALL");
        assertThat(request.incidentTitle()).isEqualTo("Undeliverable notification");
    }

    @Test
    @DisplayName("says it is a platform alert and not the incident's severity")
    void bodyExplainsTheSeverity() {
        service(OPERATOR_EMAIL).undeliverable(
                INCIDENT_ID, TENANT_ID, "IncidentOpenedEvent", UndeliverableReason.NO_ONCALL);

        final ArgumentCaptor<NotificationRequest> captor =
                ArgumentCaptor.forClass(NotificationRequest.class);
        then(emailChannel).should().send(captor.capture());
        assertThat(captor.getValue().message()).contains("not the incident's");
    }

    @Test
    @DisplayName("emails once per tenant and reason within the interval, however many entries fail")
    void rateLimitsPerTenantAndReason() {
        final OperatorAlertService service = service(OPERATOR_EMAIL);

        for (int i = 0; i < 5; i++) {
            service.undeliverable(UUID.randomUUID(), TENANT_ID,
                    "IncidentOpenedEvent", UndeliverableReason.NO_ONCALL);
        }

        then(emailChannel).should(times(1)).send(any());
    }

    @Test
    @DisplayName("a different tenant, or a different reason, is alerted separately")
    void separateKeysAreAlertedSeparately() {
        final OperatorAlertService service = service(OPERATOR_EMAIL);

        service.undeliverable(INCIDENT_ID, "tenant-a", "IncidentOpenedEvent",
                UndeliverableReason.NO_ONCALL);
        service.undeliverable(INCIDENT_ID, "tenant-b", "IncidentOpenedEvent",
                UndeliverableReason.NO_ONCALL);
        service.undeliverable(INCIDENT_ID, "tenant-a", "IncidentOpenedEvent",
                UndeliverableReason.ONCALL_UNAVAILABLE);

        then(emailChannel).should(times(3)).send(any());
    }

    @Test
    @DisplayName("emails again once the interval has passed")
    void emailsAgainAfterTheInterval() {
        final OperatorAlertService service = service(OPERATOR_EMAIL);

        service.undeliverable(INCIDENT_ID, TENANT_ID, "IncidentOpenedEvent",
                UndeliverableReason.NO_ONCALL);
        clock.advance(MIN_INTERVAL.plusSeconds(1));
        service.undeliverable(INCIDENT_ID, TENANT_ID, "IncidentOpenedEvent",
                UndeliverableReason.NO_ONCALL);

        then(emailChannel).should(times(2)).send(any());
    }

    @Test
    @DisplayName("a failed send does not silence the operator for the whole interval: it is retried after a minute")
    void failedSendIsRetriedAfterAMinute() {
        willThrow(new NotificationException("EMAIL", OPERATOR_EMAIL, "smtp down", null))
                .given(emailChannel).send(any());
        final OperatorAlertService service = service(OPERATOR_EMAIL);

        service.undeliverable(INCIDENT_ID, TENANT_ID, "IncidentOpenedEvent",
                UndeliverableReason.NO_ONCALL);
        // still inside the retry delay: not retried, or every entry would block the
        // scheduler on a failing send during an SMTP outage
        clock.advance(java.time.Duration.ofSeconds(30));
        service.undeliverable(INCIDENT_ID, TENANT_ID, "IncidentOpenedEvent",
                UndeliverableReason.NO_ONCALL);
        then(emailChannel).should(times(1)).send(any());

        clock.advance(java.time.Duration.ofSeconds(31));
        service.undeliverable(INCIDENT_ID, TENANT_ID, "IncidentOpenedEvent",
                UndeliverableReason.NO_ONCALL);
        then(emailChannel).should(times(2)).send(any());
    }

    @Test
    @DisplayName("fails closed when the limiter is full of live keys: a new pair is suppressed, not emailed")
    void failsClosedWhenFull() {
        final OperatorAlertService service = service(OPERATOR_EMAIL);

        for (int i = 0; i < OperatorAlertService.MAX_TRACKED_ALERTS; i++) {
            service.undeliverable(INCIDENT_ID, "tenant-" + i, "IncidentOpenedEvent",
                    UndeliverableReason.NO_ONCALL);
        }
        then(emailChannel).should(times(OperatorAlertService.MAX_TRACKED_ALERTS)).send(any());

        service.undeliverable(INCIDENT_ID, "one-tenant-too-many", "IncidentOpenedEvent",
                UndeliverableReason.NO_ONCALL);

        then(emailChannel).should(times(OperatorAlertService.MAX_TRACKED_ALERTS)).send(any());
    }

    @Test
    @DisplayName("frees the space of expired keys, so the limiter recovers after the interval")
    void recoversAfterTheInterval() {
        final OperatorAlertService service = service(OPERATOR_EMAIL);
        for (int i = 0; i < OperatorAlertService.MAX_TRACKED_ALERTS; i++) {
            service.undeliverable(INCIDENT_ID, "tenant-" + i, "IncidentOpenedEvent",
                    UndeliverableReason.NO_ONCALL);
        }

        clock.advance(MIN_INTERVAL.plusSeconds(1));
        service.undeliverable(INCIDENT_ID, "one-tenant-too-many", "IncidentOpenedEvent",
                UndeliverableReason.NO_ONCALL);

        then(emailChannel).should(times(OperatorAlertService.MAX_TRACKED_ALERTS + 1)).send(any());
    }

    @Test
    @DisplayName("sends nothing when no operator address is configured — there is no default")
    void noEmailWhenNotConfigured() {
        service(null).undeliverable(
                INCIDENT_ID, TENANT_ID, "IncidentOpenedEvent", UndeliverableReason.NO_ONCALL);
        service("  ").undeliverable(
                INCIDENT_ID, TENANT_ID, "IncidentOpenedEvent", UndeliverableReason.NO_ONCALL);

        then(emailChannel).should(never()).send(any());
    }

    @Test
    @DisplayName("sends nothing when the email channel is disabled")
    void noEmailWhenChannelDisabled() {
        given(emailChannel.isEnabled()).willReturn(false);

        service(OPERATOR_EMAIL).undeliverable(
                INCIDENT_ID, TENANT_ID, "IncidentOpenedEvent", UndeliverableReason.NO_ONCALL);

        then(emailChannel).should(never()).send(any());
    }

    @Test
    @DisplayName("does not fail when there is no email channel at all")
    void noEmailChannel() {
        final OperatorAlertService noChannels =
                new OperatorAlertService(List.of(), properties(OPERATOR_EMAIL), clock);

        assertThatCode(() -> noChannels.undeliverable(
                INCIDENT_ID, TENANT_ID, "IncidentOpenedEvent", UndeliverableReason.NO_ONCALL))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("swallows a failure to send — an alert must not fail the queue entry")
    void swallowsSendFailure() {
        willThrow(new NotificationException("EMAIL", OPERATOR_EMAIL, "smtp down", null))
                .given(emailChannel).send(any());

        assertThatCode(() -> service(OPERATOR_EMAIL).undeliverable(
                INCIDENT_ID, TENANT_ID, "IncidentOpenedEvent", UndeliverableReason.NO_ONCALL))
                .doesNotThrowAnyException();
    }
}
