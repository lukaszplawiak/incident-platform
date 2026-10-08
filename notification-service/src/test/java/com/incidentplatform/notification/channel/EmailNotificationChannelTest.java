package com.incidentplatform.notification.channel;

import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.shared.domain.Severity;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.incidentplatform.notification.config.NotificationChannelProperties;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import jakarta.mail.Session;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;
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
        final NotificationChannelProperties properties = new NotificationChannelProperties(
                new NotificationChannelProperties.Channels(
                        new NotificationChannelProperties.Email(true, FROM_ADDRESS),
                        new NotificationChannelProperties.Slack(true, "secret", "http://localhost", null, null),
                        new NotificationChannelProperties.Sms(true, "+1234567890")),
                new NotificationChannelProperties.OperatorAlert("operator@test.com", null));
        channel = new EmailNotificationChannel(mailSender, properties);
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
            final EmailNotificationChannel disabled =
                    new EmailNotificationChannel(mailSender,
                            new NotificationChannelProperties(
                                    new NotificationChannelProperties.Channels(
                                            new NotificationChannelProperties.Email(false, FROM_ADDRESS),
                                            new NotificationChannelProperties.Slack(true, "secret", "http://localhost", null, null),
                                            new NotificationChannelProperties.Sms(true, "+1234")),
                                    new NotificationChannelProperties.OperatorAlert("operator@test.com", null)));
            assertThat(disabled.isEnabled()).isFalse();
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
        @DisplayName("escapes the tenant id in the HTML body (it reaches the operator too)")
        void shouldEscapeTheTenantId() throws Exception {
            final MimeMessage mimeMessage = new MimeMessage((Session) null);
            given(mailSender.createMimeMessage()).willReturn(mimeMessage);
            final NotificationRequest request = new NotificationRequest(
                    UUID.randomUUID(), "<script>alert(1)</script>", "IncidentOpenedEvent",
                    "oncall@test.com", "[HIGH] subject", "message", Severity.HIGH, "title");

            channel.send(request);

            final String body = String.valueOf(mimeMessage.getContent());
            assertThat(body).doesNotContain("<script>");
            assertThat(body).contains("&lt;script&gt;");
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
            // Backlog #0-93: the platform's reason, never the mail library's text.
            assertThatThrownBy(() -> channel.send(request))
                    .isInstanceOfSatisfying(NotificationException.class, e -> assertThat(e.reason())
                            .isEqualTo(NotificationFailureReason.EMAIL_FAILED))
                    .hasMessage("EMAIL_FAILED: Email delivery failed")
                    .hasMessageNotContaining("SMTP connection refused");
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

    /**
     * Backlog #0-93: a failed send is recorded by the platform's reason, read
     * from the exception and everything it wraps, in the shapes Spring's
     * JavaMailSender and Jakarta Mail produce.
     */
    @Nested
    @DisplayName("classify")
    class Classify {

        private Address[] addresses(String... values) throws Exception {
            final Address[] result = new Address[values.length];
            for (int i = 0; i < values.length; i++) {
                result[i] = new InternetAddress(values[i]);
            }
            return result;
        }

        @Test
        @DisplayName("authentication to the mail server failed: EMAIL_AUTHENTICATION_FAILED, first even beside a "
                + "rejected address")
        void authentication() throws Exception {
            assertThat(classify(new MailAuthenticationException("535 auth failed")))
                    .isEqualTo(NotificationFailureReason.EMAIL_AUTHENTICATION_FAILED);
            assertThat(classify(new MailSendException(Map.of("m1",
                    new AuthenticationFailedException("535"),
                    "m2", new SendFailedException("Invalid", null, addresses(), addresses(),
                            addresses("x@y.example"))))))
                    .isEqualTo(NotificationFailureReason.EMAIL_AUTHENTICATION_FAILED);
        }

        private NotificationFailureReason classify(Throwable failure) {
            return EmailNotificationChannel.classify(failure, "nobody@acme.example");
        }

        /** What Angus Mail throws for a refused RCPT: the per-address detail as the next exception. */
        private MailSendException refusedRcpt(int code, String reply) throws Exception {
            final SMTPAddressFailedException detail = new SMTPAddressFailedException(
                    new InternetAddress("nobody@acme.example"), "RCPT TO:<nobody@acme.example>", code, reply);
            return new MailSendException(Map.of("m", new SendFailedException("Invalid Addresses", detail,
                    addresses(), addresses(), addresses("nobody@acme.example"))));
        }

        @Test
        @DisplayName("a refusal naming the address (5.1.x, or 550/551/553 without an enhanced code), or an address "
                + "that does not parse: EMAIL_RECIPIENT_REJECTED")
        void recipientRejected() throws Exception {
            assertThat(classify(refusedRcpt(550, "550 5.1.1 <nobody@acme.example>: unknown")))
                    .isEqualTo(NotificationFailureReason.EMAIL_RECIPIENT_REJECTED);
            assertThat(classify(refusedRcpt(553, "553 mailbox name not allowed")))
                    .isEqualTo(NotificationFailureReason.EMAIL_RECIPIENT_REJECTED);
            assertThat(classify(new AddressException("Illegal address", "nobody@acme.example")))
                    .isEqualTo(NotificationFailureReason.EMAIL_RECIPIENT_REJECTED);
        }

        /** Second review: the platform's own address failing is the operator's, logged at ERROR. */
        @Test
        @DisplayName("a malformed address that is not the recipient (the platform's from), or a 5xx to another "
                + "command than RCPT, is EMAIL_FAILED")
        void notTheRecipient() throws Exception {
            assertThat(classify(new AddressException("Illegal address", "alerts@@platform")))
                    .isEqualTo(NotificationFailureReason.EMAIL_FAILED);
            assertThat(classify(new AddressException("Illegal address")))
                    .isEqualTo(NotificationFailureReason.EMAIL_FAILED);
            final SMTPAddressFailedException onMailFrom = new SMTPAddressFailedException(
                    new InternetAddress("alerts@platform.example"), "MAIL FROM:<alerts@platform.example>", 550,
                    "550 5.1.7 bad sender");
            assertThat(classify(new MailSendException(Map.of("m", new SendFailedException("x", onMailFrom,
                    addresses(), addresses(), addresses())))))
                    .isEqualTo(NotificationFailureReason.EMAIL_FAILED);
        }

        /**
         * Review: "550 5.7.1 Relaying denied" also comes as a refused address,
         * but it is the platform's relay refusing; as the tenant's rejected
         * address it would be logged at WARN and hide the misconfiguration.
         */
        @Test
        @DisplayName("a refusal on the server's policy (5.7.x, 554 without an enhanced code), or invalid addresses "
                + "without the server's detail, is EMAIL_FAILED, not the tenant's address")
        void policyRefusalIsNotTheTenants() throws Exception {
            assertThat(classify(refusedRcpt(550, "550 5.7.1 Relaying denied")))
                    .isEqualTo(NotificationFailureReason.EMAIL_FAILED);
            assertThat(classify(refusedRcpt(554, "554 Transaction failed")))
                    .isEqualTo(NotificationFailureReason.EMAIL_FAILED);
            assertThat(classify(new MailSendException(Map.of("m",
                    new SendFailedException("Invalid Addresses", null, addresses(), addresses(),
                            addresses("nobody@acme.example"))))))
                    .isEqualTo(NotificationFailureReason.EMAIL_FAILED);
            assertThat(NotificationFailureReason.EMAIL_FAILED.permanent()).as("logged at ERROR").isFalse();
        }

        @Test
        @DisplayName("no connection, a timeout or a deferral (valid but unsent, 4xx): EMAIL_TRANSPORT_UNAVAILABLE")
        void transport() throws Exception {
            assertThat(classify(new MailSendException("Mail server connection failed",
                    new MessagingException("Couldn't connect to host", new ConnectException("refused")))))
                    .isEqualTo(NotificationFailureReason.EMAIL_TRANSPORT_UNAVAILABLE);
            final MessagingException timedOut = new MessagingException("read timed out");
            timedOut.setNextException(new SocketTimeoutException());
            assertThat(classify(new MailSendException("failed", timedOut)))
                    .isEqualTo(NotificationFailureReason.EMAIL_TRANSPORT_UNAVAILABLE);
            assertThat(classify(new MailSendException(Map.of("m",
                    new SendFailedException("452 try later", null, addresses(), addresses("a@acme.example"),
                            addresses())))))
                    .isEqualTo(NotificationFailureReason.EMAIL_TRANSPORT_UNAVAILABLE);
            assertThat(classify(refusedRcpt(450, "450 4.2.1 mailbox busy")))
                    .isEqualTo(NotificationFailureReason.EMAIL_TRANSPORT_UNAVAILABLE);
        }

        @Test
        @DisplayName("a cause chain that loops, or is very long, is walked once and ends")
        void boundedWalk() {
            final RuntimeException a = new RuntimeException("a");
            final RuntimeException b = new RuntimeException("b", a);
            a.initCause(b);
            assertThat(classify(a)).isEqualTo(NotificationFailureReason.EMAIL_FAILED);

            Throwable deep = new ConnectException("refused");
            for (int i = 0; i < 100; i++) {
                deep = new RuntimeException("level " + i, deep);
            }
            assertThat(classify(deep))
                    .as("past the first 32, a cause is not looked at")
                    .isEqualTo(NotificationFailureReason.EMAIL_FAILED);
        }

        @Test
        @DisplayName("anything else, or nothing at all: EMAIL_FAILED")
        void other() {
            assertThat(classify(new IllegalStateException("odd")))
                    .isEqualTo(NotificationFailureReason.EMAIL_FAILED);
            assertThat(classify(null))
                    .isEqualTo(NotificationFailureReason.EMAIL_FAILED);
        }

        @Test
        @DisplayName("send() records the reason, and the mail server's reply only as the cause")
        void sendCarriesReason() throws Exception {
            given(mailSender.createMimeMessage()).willReturn(new MimeMessage((Session) null));
            final MailSendException rejected = refusedRcpt(550,
                    "550 5.1.1 <oncall@test.com> unknown; relay smtp.internal:587");
            willThrow(rejected).given(mailSender).send(any(MimeMessage.class));

            assertThatThrownBy(() -> channel.send(buildRequest("oncall@test.com", Severity.CRITICAL)))
                    .isInstanceOfSatisfying(NotificationException.class, e -> {
                        assertThat(e.reason()).isEqualTo(NotificationFailureReason.EMAIL_RECIPIENT_REJECTED);
                        assertThat(e.getCause()).isSameAs(rejected);
                    })
                    .hasMessageNotContaining("smtp.internal")
                    .hasMessageNotContaining("550");
        }
    }
}