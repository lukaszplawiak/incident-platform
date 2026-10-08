package com.incidentplatform.notification.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.incidentplatform.notification.client.SlackWorkspaceClient;
import com.incidentplatform.notification.config.NotificationChannelProperties;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.notification.slack.SlackMessageStore;
import com.incidentplatform.notification.support.ApplicationYml;
import com.incidentplatform.shared.domain.Severity;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;

/**
 * Proves {@link SlackApiClient}'s {@code @Retry} and fallbacks run when it is
 * reached the way production reaches it: through Spring's proxy, from {@link
 * SlackNotificationChannel#send}.
 *
 * <h2>Why this test exists (backlog #0-103)</h2>
 * The retry used to sit on a method of the channel that {@code send()} called
 * on {@code this}, past the proxy, so no incident notification was ever
 * retried. Every test built the channel with {@code new}, where there is no
 * proxy either way, or ran a standalone {@code Retry}: none could see it. This
 * one builds a minimal Spring context with only the AOP and Resilience4j
 * auto-configurations, the way {@code SlackWorkspaceClientResilienceTest} did
 * for the same mistake in backlog #0-21, so a call that no longer goes through
 * the proxy, or a retry that no longer covers a 5xx or a timeout, fails here.
 */
@SpringJUnitConfig(classes = SlackApiClientResilienceTest.TestConfig.class,
        initializers = SlackApiClientResilienceTest.ApplicationYmlInitializer.class)
@TestPropertySource(properties = {
        // application.yml's own slack retry and breaker (third review of #0-104:
        // no copy to drift from it), with only these two changed: a short wait
        // between attempts, and an open state long enough that no test sees it
        // turn half-open by itself.
        "resilience4j.retry.instances.slack.wait-duration=10ms",
        "resilience4j.circuitbreaker.instances.slack.wait-duration-in-open-state=10m"
})
@DisplayName("SlackApiClient — the retry is really in the send path (backlog #0-103)")
class SlackApiClientResilienceTest {

    private static final String TENANT_ID = "acme-corp";
    private static final String ON_CALL = "U0123456789";
    private static final String BOT_TOKEN = "xoxb-test-token";
    private static final String TS = "1700000000.123456";
    private static final String POST = "/chat.postMessage";
    private static final String UPDATE = "/chat.update";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);

    // Started before the Spring context, which needs its port for the base URL.
    private static final WireMockServer WIRE_MOCK =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @Autowired
    private SlackApiClient slackApi;

    @Autowired
    private SlackNotificationChannel channel;

    @Autowired
    private SlackMessageStore messageStore;

    @Autowired
    private RetryRegistry retryRegistry;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void reset() {
        WIRE_MOCK.resetAll();
        clearInvocations(messageStore);
        // Shared by every test of the context: the failures one test makes
        // would otherwise open it for the next.
        breaker().reset();
    }

    private CircuitBreaker breaker() {
        return circuitBreakerRegistry.circuitBreaker(SlackApiClient.CIRCUIT_BREAKER_NAME);
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @Test
    @DisplayName("the injected client is Spring's proxy, not the bare object")
    void clientIsProxied() {
        assertThat(AopUtils.isAopProxy(slackApi)).isTrue();
    }

    @Test
    @DisplayName("send(): two 503s, then ok — the DM is delivered on the third attempt and its ts stored")
    void sendRetriesServerErrors() {
        answers(POST, 503, 503);

        channel.send(request());

        WIRE_MOCK.verify(3, postRequestedFor(urlPathEqualTo(POST)));
        then(messageStore).should().save(request().incidentId(), ON_CALL, TENANT_ID, TS);
    }

    @Test
    @DisplayName("send(): a 503 on every attempt — SLACK_UNAVAILABLE (http_503) from the fallback after 3 attempts")
    void sendGivesUpAfterTheRetries() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(POST)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> channel.send(request()))
                .isInstanceOfSatisfying(NotificationException.class, e -> {
                    assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_UNAVAILABLE);
                    assertThat(e.detail()).isEqualTo("http_503");
                });
        WIRE_MOCK.verify(3, postRequestedFor(urlPathEqualTo(POST)));
    }

    @Test
    @DisplayName("send(): a Slack that does not answer within the read timeout is retried, then SLACK_UNAVAILABLE")
    void sendRetriesReadTimeout() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(POST)).willReturn(okAnswer()
                .withFixedDelay((int) READ_TIMEOUT.multipliedBy(4).toMillis())));

        assertThatThrownBy(() -> channel.send(request()))
                .isInstanceOfSatisfying(NotificationException.class, e -> {
                    assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_UNAVAILABLE);
                    assertThat(e.detail()).isNull();
                });
        WIRE_MOCK.verify(3, postRequestedFor(urlPathEqualTo(POST)));
    }

    /**
     * Review of #0-103: the read timeout must bound the whole answer, not only
     * the wait for its headers, or a Slack that trickles its body holds the
     * scheduler's thread past the worst case it checks against its lock.
     */
    @Test
    @DisplayName("send(): an answer whose body trickles in past the read timeout times out too, and is retried")
    void sendTimesOutOnATricklingBody() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(POST)).willReturn(okAnswer()
                .withChunkedDribbleDelay(5, (int) READ_TIMEOUT.multipliedBy(8).toMillis())));
        final AtomicInteger retries = new AtomicInteger();
        retryRegistry.retry(SlackApiClient.RETRY_NAME).getEventPublisher().onRetry(event -> retries.incrementAndGet());

        // Counted by the retry's own events, not by time or by WireMock's journal
        // (which records a request only once its answer is served, and the client
        // gave up on it): a timeout bounding only the headers would have read the
        // trickle to its end, an ok answer, and neither failed nor retried.
        assertThatThrownBy(() -> channel.send(request()))
                .isInstanceOfSatisfying(NotificationException.class,
                        e -> assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_UNAVAILABLE));
        assertThat(retries).hasValue(2);
    }

    /** Second review of #0-103: the request carries the tenant's bot token. */
    @Test
    @DisplayName("send(): a redirect is not followed, so the bot token never goes to another address")
    void sendDoesNotFollowRedirects() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(POST)).willReturn(aResponse().withStatus(307)
                .withHeader("Location", "http://localhost:" + WIRE_MOCK.port() + "/elsewhere")));
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/elsewhere")).willReturn(okAnswer()));

        assertThatThrownBy(() -> channel.send(request()))
                .isInstanceOf(NotificationException.class);
        WIRE_MOCK.verify(0, postRequestedFor(urlPathEqualTo("/elsewhere")));
    }

    @Test
    @DisplayName("send(): a 401 is not retried, and the fallback classifies it — Slack's body not kept")
    void sendDoesNotRetryClientErrors() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(POST))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"secret body\"}")));

        assertThatThrownBy(() -> channel.send(request()))
                .isInstanceOfSatisfying(NotificationException.class, e -> {
                    assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_AUTH_FAILED);
                    assertThat(e.detail()).isEqualTo("http_401");
                })
                .hasMessageNotContaining("secret");
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(POST)));
    }

    @Test
    @DisplayName("send(): ok:false is not retried, even with a code Slack calls transient (backlog #0-93)")
    void sendDoesNotRetryOkFalse() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(POST)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":false,\"error\":\"internal_error\"}")));

        assertThatThrownBy(() -> channel.send(request()))
                .isInstanceOfSatisfying(NotificationException.class,
                        e -> assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_UNAVAILABLE));
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(POST)));
    }

    @Test
    @DisplayName("the ACK update: a 503, then ok — updated on the second attempt")
    void updateRetriesServerErrors() {
        answers(UPDATE, 503);

        channel.updateMessageAfterAck(ON_CALL, TS, "Jane", request(), BOT_TOKEN);

        WIRE_MOCK.verify(2, postRequestedFor(urlPathEqualTo(UPDATE)));
    }

    /**
     * Backlog #0-104: after enough network errors or 5xx the breaker opens, and
     * a send then fails at once, without calling Slack and without a retry
     * (the retry wraps the breaker; CallNotPermittedException is not retried).
     */
    @Test
    @DisplayName("breaker: 5xx open it, then a send fails at once as SLACK_UNAVAILABLE (circuit_open), Slack not called")
    void breakerOpensOnServerErrorsAndFailsFast() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(POST)).willReturn(aResponse().withStatus(503)));
        // The counts below assume application.yml's 3 attempts per message.
        assertThat(retryRegistry.retry(SlackApiClient.RETRY_NAME).getRetryConfig().getMaxAttempts()).isEqualTo(3);

        // 3 attempts, then 2 more: the fifth failed call opens it (5 of 5).
        assertThatThrownBy(() -> channel.send(request())).isInstanceOf(NotificationException.class);
        assertThatThrownBy(() -> channel.send(request())).isInstanceOf(NotificationException.class);
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        WIRE_MOCK.verify(5, postRequestedFor(urlPathEqualTo(POST)));

        assertThatThrownBy(() -> channel.send(request()))
                .isInstanceOfSatisfying(NotificationException.class, e -> {
                    assertThat(e.reason()).isEqualTo(NotificationFailureReason.SLACK_UNAVAILABLE);
                    assertThat(e.detail()).isEqualTo("circuit_open");
                });
        WIRE_MOCK.verify(5, postRequestedFor(urlPathEqualTo(POST)));
    }

    @Test
    @DisplayName("breaker: timeouts open it too, not only 5xx")
    void breakerOpensOnTimeouts() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(POST)).willReturn(okAnswer()
                .withFixedDelay((int) READ_TIMEOUT.multipliedBy(4).toMillis())));

        assertThatThrownBy(() -> channel.send(request())).isInstanceOf(NotificationException.class);
        assertThatThrownBy(() -> channel.send(request())).isInstanceOf(NotificationException.class);

        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("breaker: half-open, a trial call that fails opens it again; the rest of that send is refused")
    void breakerReopensAfterAFailedTrial() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(POST)).willReturn(aResponse().withStatus(503)));
        breaker().transitionToOpenState();
        breaker().transitionToHalfOpenState();

        assertThatThrownBy(() -> channel.send(request()))
                .isInstanceOfSatisfying(NotificationException.class,
                        e -> assertThat(e.detail()).isEqualTo("circuit_open"));

        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        // The one trial reached Slack; the retry's next attempt met the reopened breaker.
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(POST)));
    }

    @Test
    @DisplayName("breaker: the ACK update shares it — open, an update fails at once without calling Slack")
    void breakerCoversTheAckUpdate() {
        breaker().transitionToOpenState();

        assertThatThrownBy(() -> channel.updateMessageAfterAck(ON_CALL, TS, "Jane", request(), BOT_TOKEN))
                .isInstanceOfSatisfying(NotificationException.class,
                        e -> assertThat(e.detail()).isEqualTo("circuit_open"));
        WIRE_MOCK.verify(0, postRequestedFor(urlPathEqualTo(UPDATE)));
    }

    /** Backlog #0-104: no one tenant's workspace can open it for the others. */
    @Test
    @DisplayName("breaker: a revoked token (401), a rate limit (429) and ok:false never open it")
    void breakerIgnoresWhatATenantsWorkspaceAnswers() {
        for (final int status : new int[] {401, 429}) {
            WIRE_MOCK.stubFor(post(urlPathEqualTo(POST)).willReturn(aResponse().withStatus(status)));
            for (int i = 0; i < 6; i++) {
                assertThatThrownBy(() -> channel.send(request())).isInstanceOf(NotificationException.class);
            }
        }
        WIRE_MOCK.stubFor(post(urlPathEqualTo(POST)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":false,\"error\":\"invalid_auth\"}")));
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> channel.send(request())).isInstanceOf(NotificationException.class);
        }

        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("breaker: half-open, a trial call that succeeds closes it and the message is delivered")
    void breakerClosesAfterASuccessfulTrial() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(POST)).willReturn(okAnswer()));
        breaker().transitionToOpenState();
        breaker().transitionToHalfOpenState();

        channel.send(request());

        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        then(messageStore).should().save(request().incidentId(), ON_CALL, TENANT_ID, TS);
    }

    /** {@code path} answers each status in turn, then ok from there on. */
    private static void answers(String path, int... statuses) {
        String state = Scenario.STARTED;
        for (int i = 0; i < statuses.length; i++) {
            final String next = "attempt-" + (i + 2);
            WIRE_MOCK.stubFor(post(urlPathEqualTo(path)).inScenario(path)
                    .whenScenarioStateIs(state)
                    .willReturn(aResponse().withStatus(statuses[i]))
                    .willSetStateTo(next));
            state = next;
        }
        WIRE_MOCK.stubFor(post(urlPathEqualTo(path)).inScenario(path)
                .whenScenarioStateIs(state)
                .willReturn(okAnswer()));
    }

    private static ResponseDefinitionBuilder okAnswer() {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":true,\"ts\":\"" + TS + "\"}");
    }

    private static NotificationRequest request() {
        return new NotificationRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000103"), TENANT_ID, "IncidentOpenedEvent",
                ON_CALL, "[CRITICAL] High CPU", "message", Severity.CRITICAL, "High CPU");
    }

    static class ApplicationYmlInitializer
            implements org.springframework.context.ApplicationContextInitializer<
                    org.springframework.context.ConfigurableApplicationContext> {
        @Override
        public void initialize(org.springframework.context.ConfigurableApplicationContext context) {
            ApplicationYml.addLast(context.getEnvironment());
        }
    }

    @Configuration
    @ImportAutoConfiguration({
            AopAutoConfiguration.class,
            CircuitBreakerAutoConfiguration.class,
            RetryAutoConfiguration.class
    })
    static class TestConfig {

        @Bean
        NotificationChannelProperties notificationChannelProperties() {
            return new NotificationChannelProperties(
                    new NotificationChannelProperties.Channels(
                            new NotificationChannelProperties.Email(true, "alerts@test.com"),
                            new NotificationChannelProperties.Slack(true, "signing-secret",
                                    "http://localhost:" + WIRE_MOCK.port(), Duration.ofSeconds(3), READ_TIMEOUT),
                            new NotificationChannelProperties.Sms(true, "+1234567890")),
                    new NotificationChannelProperties.OperatorAlert("operator@test.com", null));
        }

        // HTTP/1.1: WireMock does not speak HTTP/2 over plain HTTP (see
        // SlackNotificationChannelSendTest). The timeouts are applied by the
        // client itself, from the properties above, as in production.
        @Bean
        SlackApiClient slackApiClient(NotificationChannelProperties properties, RetryRegistry retryRegistry) {
            return new SlackApiClient(RestClient.builder(),
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1),
                    new ObjectMapper(), properties, retryRegistry);
        }

        @Bean
        SlackMessageStore slackMessageStore() {
            return mock(SlackMessageStore.class);
        }

        @Bean
        SlackNotificationChannel slackNotificationChannel(SlackApiClient slackApi,
                                                          NotificationChannelProperties properties,
                                                          SlackMessageStore messageStore) {
            // No broadcast: each send is the one DM, so the request counts are the DM's attempts.
            final SlackWorkspaceClient workspaces = mock(SlackWorkspaceClient.class);
            given(workspaces.getWorkspace(TENANT_ID)).willReturn(Optional.of(
                    new SlackWorkspaceClient.SlackWorkspaceInfo(BOT_TOKEN, null, false, null)));
            return new SlackNotificationChannel(slackApi, properties, messageStore, workspaces,
                    new SimpleMeterRegistry());
        }
    }
}
