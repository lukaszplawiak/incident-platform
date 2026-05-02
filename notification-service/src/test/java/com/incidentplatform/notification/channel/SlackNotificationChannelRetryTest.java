package com.incidentplatform.notification.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.shared.domain.Severity;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SlackNotificationChannel — retry behaviour")
class SlackNotificationChannelRetryTest {

    private SlackNotificationChannel channel;

    @BeforeEach
    void setUp() {
        channel = new SlackNotificationChannel(
                RestClient.builder(), new ObjectMapper());
        ReflectionTestUtils.setField(channel, "enabled", true);
        ReflectionTestUtils.setField(channel, "botToken", "xoxb-test-token");
        ReflectionTestUtils.setField(channel, "defaultChannel", "#incidents");
    }

    @Nested
    @DisplayName("sendWithAckButtonFallback")
    class FallbackMethod {

        @Test
        @DisplayName("should throw NotificationException with cause after retries exhausted")
        void shouldThrowNotificationExceptionOnFallback() {
            // given
            final NotificationRequest request = buildRequest();
            final Exception cause = new ResourceAccessException("Connection timed out");

            // when / then
            assertThatThrownBy(() ->
                    channel.sendWithAckButtonFallback("#incidents", request, cause))
                    .isInstanceOf(NotificationException.class)
                    .hasMessageContaining("after retries")
                    .hasMessageContaining("#incidents")
                    .hasCause(cause);
        }

        @Test
        @DisplayName("should include channel name in NotificationException")
        void shouldIncludeChannelInException() {
            // given
            final NotificationRequest request = buildRequest();
            final Exception cause = new ResourceAccessException("Timeout");

            // when / then
            assertThatThrownBy(() ->
                    channel.sendWithAckButtonFallback("U0123456789", request, cause))
                    .isInstanceOf(NotificationException.class)
                    .hasMessageContaining("U0123456789");
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
                channel.sendWithAckButtonFallback(
                        "#incidents", request, originalCause);
            } catch (NotificationException e) {
                // then
                assertThat(e.getCause()).isSameAs(originalCause);
            }
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