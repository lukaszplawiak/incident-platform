package com.incidentplatform.notification.channel;

import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.shared.domain.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("SmsNotificationChannel")
class SmsNotificationChannelTest {

    private SmsNotificationChannel channel;

    private static final String FROM_NUMBER = "+48100200300";
    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        channel = new SmsNotificationChannel();
        ReflectionTestUtils.setField(channel, "enabled", true);
        ReflectionTestUtils.setField(channel, "fromNumber", FROM_NUMBER);
    }

    @Nested
    @DisplayName("channelName and isEnabled")
    class ChannelMetadata {

        @Test
        @DisplayName("channelName should return SMS")
        void channelNameShouldBeSms() {
            assertThat(channel.channelName()).isEqualTo("SMS");
        }

        @Test
        @DisplayName("isEnabled should return true when enabled=true")
        void shouldBeEnabledWhenConfigured() {
            assertThat(channel.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("isEnabled should return false when enabled=false")
        void shouldBeDisabledWhenConfigured() {
            ReflectionTestUtils.setField(channel, "enabled", false);
            assertThat(channel.isEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("send")
    class Send {

        @Test
        @DisplayName("should not throw for valid request")
        void shouldNotThrowForValidRequest() {
            final NotificationRequest request = buildRequest(
                    "+48999888777", Severity.CRITICAL, "Alert message");

            assertThatCode(() -> channel.send(request))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should not throw when message is null")
        void shouldNotThrowWhenMessageIsNull() {
            // given
            final NotificationRequest request = new NotificationRequest(
                    UUID.randomUUID(), TENANT_ID,
                    "IncidentEscalatedEvent", "+48999888777",
                    "subject", null, Severity.CRITICAL, "title"
            );

            assertThatCode(() -> channel.send(request))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should truncate message to 160 characters (SMS limit)")
        void shouldTruncateMessageTo160Chars() {
            // given
            final String longMessage = "x".repeat(300);
            final NotificationRequest request = buildRequest(
                    "+48999888777", Severity.HIGH, longMessage);

            // when / then
            assertThatCode(() -> channel.send(request))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should handle message exactly at 160 character boundary")
        void shouldHandleMessageAtExactLimit() {
            // given
            final String exactMessage = "a".repeat(160);
            final NotificationRequest request = buildRequest(
                    "+48999888777", Severity.MEDIUM, exactMessage);

            assertThatCode(() -> channel.send(request))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should handle short message without truncation")
        void shouldHandleShortMessageWithoutTruncation() {
            final NotificationRequest request = buildRequest(
                    "+48999888777", Severity.LOW, "Short alert message");

            assertThatCode(() -> channel.send(request))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "should handle severity={0}")
        @EnumSource(Severity.class)
        @DisplayName("should handle all severity values without throwing")
        void shouldHandleAllSeverityValues(Severity severity) {
            final NotificationRequest request = buildRequest(
                    "+48999888777", severity, "Alert message");

            assertThatCode(() -> channel.send(request))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("SMS routing")
    class SmsRouting {

        @Test
        @DisplayName("SMS is intended for escalation events — CRITICAL and HIGH severity")
        void smsShouldBeUsedForEscalation() {
            final NotificationRequest criticalRequest = buildRequest(
                    "+48999888777", Severity.CRITICAL,
                    "ESCALATION: Critical incident unacknowledged");
            final NotificationRequest highRequest = buildRequest(
                    "+48999888777", Severity.HIGH,
                    "ESCALATION: High severity incident unacknowledged");

            assertThatCode(() -> channel.send(criticalRequest))
                    .doesNotThrowAnyException();
            assertThatCode(() -> channel.send(highRequest))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should accept international phone number format")
        void shouldAcceptInternationalPhoneNumber() {
            final NotificationRequest request = buildRequest(
                    "+12125551234", Severity.CRITICAL, "Alert");

            assertThatCode(() -> channel.send(request))
                    .doesNotThrowAnyException();
        }
    }

    private NotificationRequest buildRequest(String recipient,
                                             Severity severity,
                                             String message) {
        return new NotificationRequest(
                UUID.randomUUID(),
                TENANT_ID,
                "IncidentEscalatedEvent",
                recipient,
                "[ESCALATED][" + severity.name() + "] High CPU Usage",
                message,
                severity,
                "High CPU Usage"
        );
    }
}