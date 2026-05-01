package com.incidentplatform.notification.channel;

import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.shared.domain.Severity;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.mail.Session;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailNotificationChannel")
class EmailNotificationChannelTest {

    @Mock
    private JavaMailSender mailSender;

    private EmailNotificationChannel channel;

    private static final String FROM_ADDRESS = "alerts@incidentplatform.com";
    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        channel = new EmailNotificationChannel(mailSender);
        ReflectionTestUtils.setField(channel, "enabled", true);
        ReflectionTestUtils.setField(channel, "fromAddress", FROM_ADDRESS);
    }

    @Nested
    @DisplayName("channelName and isEnabled")
    class ChannelMetadata {

        @Test
        @DisplayName("channelName should return EMAIL")
        void channelNameShouldBeEmail() {
            assertThat(channel.channelName()).isEqualTo("EMAIL");
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
        @DisplayName("should send email via JavaMailSender")
        void shouldSendEmailViaMailSender() throws Exception {
            // given
            final MimeMessage mimeMessage =
                    new MimeMessage((Session) null);
            given(mailSender.createMimeMessage()).willReturn(mimeMessage);

            final NotificationRequest request = buildRequest(
                    "oncall@test.com", Severity.CRITICAL);

            // when
            channel.send(request);

            // then
            then(mailSender).should().send(mimeMessage);
        }

        @Test
        @DisplayName("should set correct recipient, subject and from")
        void shouldSetCorrectEmailFields() throws Exception {
            // given
            final MimeMessage mimeMessage =
                    new MimeMessage((Session) null);
            given(mailSender.createMimeMessage()).willReturn(mimeMessage);

            final NotificationRequest request = buildRequest(
                    "oncall@test.com", Severity.HIGH);

            // when
            channel.send(request);

            // then
            assertThat(mimeMessage.getAllRecipients()).isNotNull();
            assertThat(mimeMessage.getFrom()).isNotNull();
            assertThat(mimeMessage.getSubject())
                    .isEqualTo("[HIGH] High CPU Usage");
        }

        @Test
        @DisplayName("should throw NotificationException when mail sending fails")
        void shouldThrowNotificationExceptionOnMailFailure() throws Exception {
            // given
            final MimeMessage mimeMessage =
                    new MimeMessage((Session) null);
            given(mailSender.createMimeMessage()).willReturn(mimeMessage);
            willThrow(new RuntimeException("SMTP connection refused"))
                    .given(mailSender).send(any(MimeMessage.class));

            final NotificationRequest request = buildRequest(
                    "oncall@test.com", Severity.CRITICAL);

            // when / then
            assertThatThrownBy(() -> channel.send(request))
                    .isInstanceOf(NotificationException.class)
                    .hasMessageContaining("Email sending failed")
                    .hasMessageContaining("SMTP connection refused");
        }

        @Test
        @DisplayName("should preserve original exception as cause")
        void shouldPreserveOriginalCause() throws Exception {
            // given
            final MimeMessage mimeMessage =
                    new MimeMessage((Session) null);
            given(mailSender.createMimeMessage()).willReturn(mimeMessage);
            final RuntimeException smtpError =
                    new RuntimeException("Authentication failed");
            willThrow(smtpError).given(mailSender).send(any(MimeMessage.class));

            final NotificationRequest request = buildRequest(
                    "oncall@test.com", Severity.CRITICAL);

            // when / then
            assertThatThrownBy(() -> channel.send(request))
                    .isInstanceOf(NotificationException.class)
                    .hasCause(smtpError);
        }
    }

    @Nested
    @DisplayName("HTML body color per severity")
    class HtmlBodyColor {

        @Test
        @DisplayName("CRITICAL should produce red color in HTML body")
        void criticalShouldBeRed() throws Exception {
            assertHtmlBodyContainsColor(Severity.CRITICAL, "#FF0000");
        }

        @Test
        @DisplayName("HIGH should produce orange color in HTML body")
        void highShouldBeOrange() throws Exception {
            assertHtmlBodyContainsColor(Severity.HIGH, "#FF6600");
        }

        @Test
        @DisplayName("MEDIUM should produce yellow color in HTML body")
        void mediumShouldBeYellow() throws Exception {
            assertHtmlBodyContainsColor(Severity.MEDIUM, "#FFAA00");
        }

        @Test
        @DisplayName("LOW should produce green color in HTML body")
        void lowShouldBeGreen() throws Exception {
            assertHtmlBodyContainsColor(Severity.LOW, "#00AA00");
        }

        private void assertHtmlBodyContainsColor(Severity severity,
                                                 String expectedColor)
                throws Exception {
            // given
            final MimeMessage mimeMessage =
                    new MimeMessage((Session) null);
            given(mailSender.createMimeMessage()).willReturn(mimeMessage);

            final NotificationRequest request = buildRequest(
                    "oncall@test.com", severity);

            // when
            channel.send(request);

            // then
            final String content = mimeMessage.getContent().toString();
            assertThat(content).contains(expectedColor);
        }
    }

    private NotificationRequest buildRequest(String recipient,
                                             Severity severity) {
        return new NotificationRequest(
                UUID.randomUUID(),
                TENANT_ID,
                "IncidentOpenedEvent",
                recipient,
                "[" + severity.name() + "] High CPU Usage",
                "CPU exceeded 95% on prod-server-1",
                severity,
                "High CPU Usage"
        );
    }
}