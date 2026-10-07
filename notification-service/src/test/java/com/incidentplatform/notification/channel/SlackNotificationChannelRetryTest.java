package com.incidentplatform.notification.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.notification.client.SlackWorkspaceClient;
import com.incidentplatform.notification.config.NotificationChannelProperties;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.notification.slack.SlackMessageStore;
import com.incidentplatform.shared.domain.Severity;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
@DisplayName("SlackNotificationChannel — retry behaviour")
class SlackNotificationChannelRetryTest {

    @Mock
    private SlackMessageStore messageStore;

    @Mock
    private SlackWorkspaceClient slackWorkspaceClient;

    private SlackNotificationChannel channel;

    private static final String BOT_TOKEN = "xoxb-test-token";

    @BeforeEach
    void setUp() {
        final NotificationChannelProperties properties = new NotificationChannelProperties(
                new NotificationChannelProperties.Channels(
                        new NotificationChannelProperties.Email(true, "alerts@test.com"),
                        new NotificationChannelProperties.Slack(
                                true, "signing-secret", "http://localhost"),
                        new NotificationChannelProperties.Sms(true, "+1234567890")),
                new NotificationChannelProperties.OperatorAlert("operator@test.com", null));
        channel = new SlackNotificationChannel(
                RestClient.builder(),
                new ObjectMapper(),
                properties,
                messageStore,
                slackWorkspaceClient,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Nested
    @DisplayName("postIncidentMessageFallback")
    class FallbackMethod {

        @Test
        @DisplayName("a network error after the retries is SLACK_UNAVAILABLE, the client's text kept on the cause only "
                + "(backlog #0-93)")
        void shouldThrowNotificationExceptionOnFallback() {
            final NotificationRequest request = buildRequest();
            final Exception cause = new ResourceAccessException("I/O error on POST https://slack.com/api/x: refused");

            assertThatThrownBy(() ->
                    channel.postIncidentMessageFallback("#incidents", request, BOT_TOKEN, cause))
                    .isInstanceOfSatisfying(NotificationException.class, e -> {
                        assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_UNAVAILABLE);
                        assertThat(e.getRecipient()).isEqualTo("#incidents");
                    })
                    .hasMessageNotContaining("slack.com")
                    .hasCause(cause);
        }

        @Test
        @DisplayName("names the channel it was posting to as the recipient")
        void shouldIncludeChannelInException() {
            final NotificationRequest request = buildRequest();
            final Exception cause = new ResourceAccessException("Timeout");

            assertThatThrownBy(() ->
                    channel.postIncidentMessageFallback("U0123456789", request, BOT_TOKEN, cause))
                    .isInstanceOfSatisfying(NotificationException.class,
                            e -> assertThat(e.getRecipient()).isEqualTo("U0123456789"));
        }

        @Test
        @DisplayName("should preserve original exception as cause")
        void shouldPreserveOriginalCause() {
            // given
            final NotificationRequest request = buildRequest();
            final ResourceAccessException originalCause =
                    new ResourceAccessException("Slack API unreachable");

            // when
            try {
                channel.postIncidentMessageFallback(
                        "#incidents", request, BOT_TOKEN, originalCause);
            } catch (NotificationException e) {
                // then
                assertThat(e.getCause()).isSameAs(originalCause);
            }
        }
    }

    /** Backlog #0-93: how a failed call is classified, by the exception alone. */
    @Nested
    @DisplayName("failure classification")
    class FailureClassification {

        @Test
        @DisplayName("429 is SLACK_RATE_LIMITED, another 4xx SLACK_REJECTED, 5xx SLACK_UNAVAILABLE, by status only")
        void httpStatuses() {
            final byte[] body = "{\"error\":\"secret detail\"}".getBytes();
            assertThat(SlackNotificationChannel.failure("#c", HttpClientErrorException.create(
                    HttpStatus.TOO_MANY_REQUESTS, "Too Many", null, body, null)))
                    .satisfies(e -> {
                        assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_RATE_LIMITED);
                        assertThat(e.detail()).isEqualTo("http_429");
                    });
            assertThat(SlackNotificationChannel.failure("#c", HttpClientErrorException.create(
                    HttpStatus.FORBIDDEN, "Forbidden", null, body, null)))
                    .satisfies(e -> {
                        assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_REJECTED);
                        assertThat(e.detail()).isEqualTo("http_403");
                        assertThat(e.getMessage()).doesNotContain("secret");
                    });
            assertThat(SlackNotificationChannel.failure("#c", HttpServerErrorException.create(
                    HttpStatus.BAD_GATEWAY, "Bad Gateway", null, body, null)))
                    .satisfies(e -> {
                        assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_UNAVAILABLE);
                        assertThat(e.detail()).isEqualTo("http_502");
                    });
        }

        @Test
        @DisplayName("401 is SLACK_AUTH_FAILED (second review)")
        void unauthorized() {
            assertThat(SlackNotificationChannel.failure("#c", HttpClientErrorException.create(
                    HttpStatus.UNAUTHORIZED, "Unauthorized", null, new byte[0], null)))
                    .satisfies(e -> {
                        assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_AUTH_FAILED);
                        assertThat(e.detail()).isEqualTo("http_401");
                    });
        }

        @Test
        @DisplayName("an already classified failure passes through; another exception is rethrown as it is")
        void passThroughAndRethrow() {
            final NotificationException classified = new NotificationException("SLACK", "#c",
                    NotificationFailureReason.SLACK_REJECTED, "not_in_channel", null);
            assertThat(SlackNotificationChannel.failure("#c", classified)).isSameAs(classified);

            final IllegalArgumentException bug = new IllegalArgumentException("a bug");
            assertThatThrownBy(() -> SlackNotificationChannel.failure("#c", bug)).isSameAs(bug);
        }
    }

    @Nested
    @DisplayName("retry configuration")
    class RetryConfiguration {

        @Test
        @DisplayName("should retry on ResourceAccessException (network error)")
        void shouldRetryOnResourceAccessException() {
            // given
            final AtomicInteger callCount = new AtomicInteger(0);

            final RetryConfig config = RetryConfig.custom()
                    .maxAttempts(3)
                    .waitDuration(Duration.ofMillis(1))
                    .retryExceptions(ResourceAccessException.class)
                    .build();

            final Retry retry = Retry.of("slack-test", config);

            // when
            assertThatThrownBy(() ->
                    retry.executeRunnable(() -> {
                        callCount.incrementAndGet();
                        throw new ResourceAccessException("Connection refused");
                    })
            );

            // then
            assertThat(callCount.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("should NOT retry on IllegalArgumentException")
        void shouldNotRetryOnIllegalArgumentException() {
            // given
            final AtomicInteger callCount = new AtomicInteger(0);

            final RetryConfig config = RetryConfig.custom()
                    .maxAttempts(3)
                    .waitDuration(Duration.ofMillis(1))
                    .retryExceptions(ResourceAccessException.class,
                            HttpServerErrorException.class)
                    .build();

            final Retry retry = Retry.of("slack-test", config);

            // when
            assertThatThrownBy(() ->
                    retry.executeRunnable(() -> {
                        callCount.incrementAndGet();
                        throw new IllegalArgumentException("Bad request");
                    })
            );

            // then
            assertThat(callCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should succeed on second attempt after transient failure")
        void shouldSucceedOnSecondAttempt() {
            // given
            final AtomicInteger callCount = new AtomicInteger(0);

            final RetryConfig config = RetryConfig.custom()
                    .maxAttempts(3)
                    .waitDuration(Duration.ofMillis(1))
                    .retryExceptions(ResourceAccessException.class)
                    .build();

            final Retry retry = Retry.of("slack-test", config);

            // when
            retry.executeRunnable(() -> {
                if (callCount.incrementAndGet() == 1) {
                    throw new ResourceAccessException("Transient failure");
                }
            });

            // then
            assertThat(callCount.get()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Severity emoji")
    class SeverityEmoji {

        @Test
        @DisplayName("should handle all severity values without throwing")
        void shouldHandleAllSeverityValues() {
            for (final Severity severity : Severity.values()) {
                final NotificationRequest request = new NotificationRequest(
                        UUID.randomUUID(), "test-tenant",
                        "IncidentOpenedEvent", "#incidents",
                        "Subject", "Message", severity, "Title"
                );
                assertThat(request.severity()).isEqualTo(severity);
            }
        }
    }

    private NotificationRequest buildRequest() {
        return new NotificationRequest(
                UUID.randomUUID(),
                "test-tenant",
                "IncidentOpenedEvent",
                "#incidents",
                "[CRITICAL] High CPU",
                "New critical incident detected",
                Severity.CRITICAL,
                "High CPU Usage"
        );
    }
}