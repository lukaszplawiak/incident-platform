package com.incidentplatform.ingestion.apikey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.incidentplatform.shared.observability.ClientFallbackMetrics;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Proves the Resilience4j annotations on {@link ApiKeyIntrospectionClientImpl}
 * run when it is reached the way production reaches it — through
 * {@link CachingApiKeyIntrospectionClient}, injected as
 * {@link ApiKeyIntrospectionClient} — the check notification-service's
 * {@code SlackWorkspaceClientResilienceTest} added after a self-invocation bug
 * (backlog #0-21). Plain unit tests build the client with {@code new} and have
 * no proxy, so they cannot see that difference.
 *
 * <p>Also pins the retry behaviour application.yml relies on: {@code @Retry}
 * wraps {@code @CircuitBreaker}, so it retries on what the breaker's fallback
 * throws ({@link ApiKeyIntrospectionUnavailableException}).
 */
@SpringJUnitConfig(ApiKeyIntrospectionClientResilienceTest.TestConfig.class)
@TestPropertySource(properties = {
        // Same values as application.yml where the test depends on them.
        "resilience4j.circuitbreaker.instances.api-key-introspection.sliding-window-size=10",
        "resilience4j.circuitbreaker.instances.api-key-introspection.minimum-number-of-calls=10",
        "resilience4j.circuitbreaker.instances.api-key-introspection.record-exceptions="
                + "org.springframework.web.client.ResourceAccessException,"
                + "org.springframework.web.client.HttpServerErrorException,"
                + "java.lang.IllegalStateException",
        "resilience4j.retry.instances.api-key-introspection.max-attempts=2",
        "resilience4j.retry.instances.api-key-introspection.wait-duration=10ms",
        "resilience4j.retry.instances.api-key-introspection.retry-exceptions="
                + "com.incidentplatform.ingestion.apikey.ApiKeyIntrospectionUnavailableException"
})
@DisplayName("ApiKeyIntrospectionClient — Resilience4j proxy is really in the call path")
class ApiKeyIntrospectionClientResilienceTest {

    private static final String PATH = "/api/v1/internal/api-keys/introspect";

    // Started before the Spring context, which needs its port for the base URL.
    private static final WireMockServer WIRE_MOCK =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @Autowired
    private ApiKeyIntrospectionClient client;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void reset() {
        WIRE_MOCK.resetAll();
        circuitBreakerRegistry.circuitBreaker("api-key-introspection").reset();
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    /** A distinct hash per test: the caching decorator must not answer from cache. */
    private static String freshHash() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    @Test
    @DisplayName("the injected ApiKeyIntrospectionClient is the caching decorator")
    void primaryIsTheCachingDecorator() {
        assertThat(client).isInstanceOf(CachingApiKeyIntrospectionClient.class);
    }

    @Test
    @DisplayName("a 503 ends in the fallback: Unavailable + fallback metric, after one retry")
    void serverErrorGoesThroughFallback() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)));
        final double before = serverErrorFallbacks();

        assertThatThrownBy(() -> client.introspect(freshHash()))
                .isInstanceOf(ApiKeyIntrospectionUnavailableException.class);

        WIRE_MOCK.verify(2, postRequestedFor(urlPathEqualTo(PATH)));
        assertThat(serverErrorFallbacks() - before).isEqualTo(2.0);
    }

    @Test
    @DisplayName("a single transient 503 is retried and the key is answered")
    void transientFailureIsRetried() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(PATH)).inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        WIRE_MOCK.stubFor(post(urlPathEqualTo(PATH)).inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"active\": false}")));

        assertThat(client.introspect(freshHash())).isEmpty();
        WIRE_MOCK.verify(2, postRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("inactive answers are successes: a burst of wrong keys never opens the circuit")
    void inactiveAnswersDoNotOpenTheCircuit() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"active\": false}")));

        for (int i = 0; i < 20; i++) {
            assertThat(client.introspect(freshHash())).isEmpty();
        }

        final var breaker = circuitBreakerRegistry.circuitBreaker("api-key-introspection");
        assertThat(breaker.getState().name()).isEqualTo("CLOSED");
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("a failure is not cached: the next request for the same key asks again")
    void failureIsNotCached() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)));
        final String hash = freshHash();

        assertThatThrownBy(() -> client.introspect(hash))
                .isInstanceOf(ApiKeyIntrospectionUnavailableException.class);
        assertThatThrownBy(() -> client.introspect(hash))
                .isInstanceOf(ApiKeyIntrospectionUnavailableException.class);

        // Two requests, each with one retry.
        WIRE_MOCK.verify(4, postRequestedFor(urlPathEqualTo(PATH)));
    }

    private double serverErrorFallbacks() {
        return meterRegistry.counter(ClientFallbackMetrics.METRIC_NAME,
                "client", "api-key-introspection", "target", "auth-service",
                "reason", "server_error").count();
    }

    @Configuration
    @ImportAutoConfiguration({
            AopAutoConfiguration.class,
            CircuitBreakerAutoConfiguration.class,
            RetryAutoConfiguration.class
    })
    @Import(CachingApiKeyIntrospectionClient.class)
    static class TestConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        // Bean name must match the @Qualifier in CachingApiKeyIntrospectionClient —
        // that wiring is part of what this test checks.
        @Bean
        ApiKeyIntrospectionClientImpl apiKeyIntrospectionClientImpl(MeterRegistry meterRegistry) {
            final ServiceTokenProvider tokenProvider = mock(ServiceTokenProvider.class);
            given(tokenProvider.getPurposeToken(anyString(), anyString()))
                    .willReturn("purpose-token");

            final HttpClient httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();

            return new ApiKeyIntrospectionClientImpl(
                    RestClient.builder()
                            .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                            .build(),
                    new ObjectMapper(),
                    tokenProvider,
                    new ClientFallbackMetrics(meterRegistry),
                    "http://localhost:" + WIRE_MOCK.port());
        }
    }
}
