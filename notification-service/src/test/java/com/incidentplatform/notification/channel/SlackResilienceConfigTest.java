package com.incidentplatform.notification.channel;

import com.incidentplatform.notification.support.ApplicationYml;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerProperties;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code slack} breaker and retry as {@code application.yml} configures
 * them, built by Resilience4j's own auto-configuration (backlog #0-104).
 * {@code SlackApiClientResilienceTest} pins its own copy of these settings to
 * test the proxy; this one checks that the shipped file says the same: only
 * network errors and 5xx count against Slack, nothing a tenant's workspace
 * answers does, and an open breaker is not retried.
 */
@DisplayName("Slack breaker and retry — application.yml (backlog #0-104)")
class SlackResilienceConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> ApplicationYml.only(context.getEnvironment()))
            .withConfiguration(AutoConfigurations.of(
                    CircuitBreakerAutoConfiguration.class, RetryAutoConfiguration.class));

    @Test
    @DisplayName("the breaker counts network errors and 5xx, ignores 4xx, 429 and ok:false, opens after at least 5 calls")
    void breakerCountsOnlySlackBeingDown() {
        runner.run(context -> {
            final CircuitBreakerConfig config = context.getBean(CircuitBreakerRegistry.class)
                    .circuitBreaker(SlackApiClient.CIRCUIT_BREAKER_NAME).getCircuitBreakerConfig();

            assertThat(config.getRecordExceptionPredicate()
                    .test(new ResourceAccessException("timed out"))).isTrue();
            assertThat(config.getRecordExceptionPredicate()
                    .test(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "", null, null, null))).isTrue();

            for (final Throwable tenantsOwn : new Throwable[] {
                    HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "", null, null, null),
                    HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "", null, null, null),
                    new NotificationException("SLACK", "#c", NotificationFailureReason.SLACK_REJECTED,
                            "not_in_channel", null)}) {
                assertThat(config.getIgnoreExceptionPredicate().test(tenantsOwn)).as(tenantsOwn.toString()).isTrue();
            }
            assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
        });
    }

    @Test
    @DisplayName("the breaker's window, threshold and open state are application.yml's, and it reports no health")
    void breakerShape() {
        runner.run(context -> {
            final CircuitBreakerConfig config = context.getBean(CircuitBreakerRegistry.class)
                    .circuitBreaker(SlackApiClient.CIRCUIT_BREAKER_NAME).getCircuitBreakerConfig();

            // Time-based (review): a count window would join failures hours apart.
            assertThat(config.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.TIME_BASED);
            assertThat(config.getSlidingWindowSize()).isEqualTo(60);
            assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
            assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(30_000L);
            assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(1);
            assertThat(config.isAutomaticTransitionFromOpenToHalfOpenEnabled()).isTrue();
            // A channel's outage must not mark the service DOWN (second review).
            assertThat(context.getBean(CircuitBreakerProperties.class).getInstances()
                    .get(SlackApiClient.CIRCUIT_BREAKER_NAME).getRegisterHealthIndicator()).isFalse();
        });
    }

    @Test
    @DisplayName("the retry does not retry an open breaker's refusal")
    void retrySkipsAnOpenBreaker() {
        runner.run(context -> {
            final RetryConfig config = context.getBean(RetryRegistry.class)
                    .retry(SlackApiClient.RETRY_NAME).getRetryConfig();

            assertThat(config.getExceptionPredicate().test(CallNotPermittedException
                    .createCallNotPermittedException(CircuitBreaker.ofDefaults("slack")))).isFalse();
            assertThat(config.getExceptionPredicate().test(new ResourceAccessException("timed out"))).isTrue();
        });
    }
}
