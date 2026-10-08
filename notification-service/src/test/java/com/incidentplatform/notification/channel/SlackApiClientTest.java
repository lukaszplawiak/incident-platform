package com.incidentplatform.notification.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.notification.config.NotificationChannelProperties;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SlackApiClient}'s fallbacks and failure classification, called
 * directly. That the retry and the fallbacks really run on a call, through
 * Spring's proxy, is {@link SlackApiClientResilienceTest}'s (backlog #0-103:
 * the tests that stood here then built the channel with {@code new} and
 * exercised a standalone {@code Retry}, so none could see that the send path
 * bypassed the proxy).
 */
@DisplayName("SlackApiClient — fallbacks and failure classification")
class SlackApiClientTest {

    private static final String BOT_TOKEN = "xoxb-test-token";
    private static final Map<String, Object> MESSAGE = Map.of("text", "hello");

    private final SlackApiClient client = new SlackApiClient(
            RestClient.builder(),
            new ObjectMapper(),
            new NotificationChannelProperties(
                    new NotificationChannelProperties.Channels(
                            new NotificationChannelProperties.Email(true, "alerts@test.com"),
                            new NotificationChannelProperties.Slack(
                                    true, "signing-secret", "http://localhost", null, null),
                            new NotificationChannelProperties.Sms(true, "+1234567890")),
                    new NotificationChannelProperties.OperatorAlert("operator@test.com", null)),
            RetryRegistry.ofDefaults());

    @Nested
    @DisplayName("fallbacks")
    class Fallbacks {

        @Test
        @DisplayName("a network error after the retries is SLACK_UNAVAILABLE, the client's text kept on the cause only "
                + "(backlog #0-93)")
        void postNetworkError() {
            final Exception cause = new ResourceAccessException("I/O error on POST https://slack.com/api/x: refused");

            assertThatThrownBy(() -> client.postMessageFallback("#incidents", MESSAGE, BOT_TOKEN, cause))
                    .isInstanceOfSatisfying(NotificationException.class, e -> {
                        assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_UNAVAILABLE);
                        assertThat(e.getRecipient()).isEqualTo("#incidents");
                        assertThat(e.getCause()).isSameAs(cause);
                    })
                    .hasMessageNotContaining("slack.com");
        }

        @Test
        @DisplayName("names the channel it was posting to as the recipient")
        void postNamesChannel() {
            assertThatThrownBy(() -> client.postMessageFallback(
                    "U0123456789", MESSAGE, BOT_TOKEN, new ResourceAccessException("Timeout")))
                    .isInstanceOfSatisfying(NotificationException.class,
                            e -> assertThat(e.getRecipient()).isEqualTo("U0123456789"));
        }

        @Test
        @DisplayName("the update's fallback throws too, so the ACK path can keep the failed channel's ts (backlog #78)")
        void updateThrows() {
            assertThatThrownBy(() -> client.updateMessageFallback("#incidents", "1.2", MESSAGE, BOT_TOKEN,
                    HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", null, null, null)))
                    .isInstanceOfSatisfying(NotificationException.class, e -> {
                        assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_UNAVAILABLE);
                        assertThat(e.detail()).isEqualTo("http_503");
                    });
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
            assertThat(SlackApiClient.failure("#c", HttpClientErrorException.create(
                    HttpStatus.TOO_MANY_REQUESTS, "Too Many", null, body, null)))
                    .satisfies(e -> {
                        assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_RATE_LIMITED);
                        assertThat(e.detail()).isEqualTo("http_429");
                    });
            assertThat(SlackApiClient.failure("#c", HttpClientErrorException.create(
                    HttpStatus.FORBIDDEN, "Forbidden", null, body, null)))
                    .satisfies(e -> {
                        assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_REJECTED);
                        assertThat(e.detail()).isEqualTo("http_403");
                        assertThat(e.getMessage()).doesNotContain("secret");
                    });
            assertThat(SlackApiClient.failure("#c", HttpServerErrorException.create(
                    HttpStatus.BAD_GATEWAY, "Bad Gateway", null, body, null)))
                    .satisfies(e -> {
                        assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_UNAVAILABLE);
                        assertThat(e.detail()).isEqualTo("http_502");
                    });
        }

        @Test
        @DisplayName("401 is SLACK_AUTH_FAILED (second review)")
        void unauthorized() {
            assertThat(SlackApiClient.failure("#c", HttpClientErrorException.create(
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
            assertThat(SlackApiClient.failure("#c", classified)).isSameAs(classified);

            final IllegalArgumentException bug = new IllegalArgumentException("a bug");
            assertThatThrownBy(() -> SlackApiClient.failure("#c", bug)).isSameAs(bug);
        }

        @Test
        @DisplayName("a checked exception, which the HTTP client never throws, becomes an IllegalStateException")
        void checkedIsWrapped() {
            final Exception checked = new Exception("checked");
            assertThatThrownBy(() -> SlackApiClient.failure("#c", checked))
                    .isInstanceOf(IllegalStateException.class)
                    .hasCause(checked);
        }
    }

    /** Review of backlog #0-103: what NotificationScheduler checks against its lock. */
    @Nested
    @DisplayName("worst-case call")
    class WorstCaseCall {

        @Test
        @DisplayName("every attempt times out on connect and read, plus every backoff wait between them")
        void exponentialBackoff() {
            final RetryConfig retry = RetryConfig.custom()
                    .maxAttempts(3)
                    .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(500), 2))
                    .build();

            // 3 x (3 s + 5 s) + 500 ms + 1 s
            assertThat(SlackApiClient.worstCaseCall(retry, Duration.ofSeconds(3), Duration.ofSeconds(5)))
                    .isEqualTo(Duration.ofMillis(25_500));
        }

        @Test
        @DisplayName("one attempt waits for nothing")
        void singleAttempt() {
            final RetryConfig retry = RetryConfig.custom().maxAttempts(1).build();

            assertThat(SlackApiClient.worstCaseCall(retry, Duration.ofSeconds(3), Duration.ofSeconds(5)))
                    .isEqualTo(Duration.ofSeconds(8));
        }

        @Test
        @DisplayName("the instance reads the registry's \"slack\" retry and its own timeouts")
        void instanceUsesRegistryAndTimeouts() {
            final RetryRegistry registry = RetryRegistry.ofDefaults();
            registry.retry(SlackApiClient.RETRY_NAME, RetryConfig.custom()
                    .maxAttempts(2).waitDuration(Duration.ofMillis(100)).build());
            final SlackApiClient configured = new SlackApiClient(RestClient.builder(), new ObjectMapper(),
                    new NotificationChannelProperties(
                            new NotificationChannelProperties.Channels(
                                    new NotificationChannelProperties.Email(true, "alerts@test.com"),
                                    new NotificationChannelProperties.Slack(true, "signing-secret",
                                            "http://localhost", Duration.ofSeconds(1), Duration.ofSeconds(2)),
                                    new NotificationChannelProperties.Sms(true, "+1234567890")),
                            null),
                    registry);

            // 2 x (1 s + 2 s) + 100 ms
            assertThat(configured.worstCaseCall()).isEqualTo(Duration.ofMillis(6_100));
        }
    }
}
