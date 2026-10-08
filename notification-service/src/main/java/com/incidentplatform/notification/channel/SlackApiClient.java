package com.incidentplatform.notification.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.notification.config.NotificationChannelProperties;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The two Slack Web API calls the platform makes, {@code chat.postMessage} and
 * {@code chat.update}, each retried and its failure classified.
 *
 * <h2>Why a bean of its own (backlog #0-103)</h2>
 * The calls and their {@code @Retry} used to be methods of {@link
 * SlackNotificationChannel}, whose {@code send()} called {@code
 * postIncidentMessage} on {@code this}: Spring's proxy sees only calls from
 * outside the bean, so for every incident notification neither the retry nor
 * the fallback ran, and one dropped connection or one Slack 5xx failed the
 * channel at once. Only the ACK update, called from {@code SlackActionService},
 * went through the proxy. The channel now calls this bean, the same split
 * {@code CachingSlackWorkspaceClient} / {@code SlackWorkspaceClientImpl} made
 * for the same mistake (backlog #0-21), and {@code SlackApiClientResilienceTest}
 * goes through Spring's proxy to show the retry, which no test did before.
 *
 * <h2>Retried, so at least once</h2>
 * {@code chat.postMessage} takes no idempotency key. A read timeout or a 5xx
 * after Slack has posted the message is retried like any other, so the on-call
 * may see it twice. Accepted (backlog #0-103): a duplicate incident message is
 * cheap, a lost one is a page nobody got. Retried are network errors (incl. the
 * timeouts below) and 5xx, per {@code resilience4j.retry.instances.slack}; an
 * {@code "ok": false} answer, a 4xx and a 429 are not.
 *
 * <h2>Timeouts (backlog #0-103)</h2>
 * The client used to be built from the bare {@code RestClient.Builder}, which
 * sets no read timeout: a Slack that accepted the connection and never
 * answered held the scheduler's thread until its processing budget ran out,
 * and, never throwing, was never retried. It now has its own, from {@code
 * notification.channels.slack.connect-timeout} / {@code read-timeout}: apart
 * from {@code notification.client.*}, which are for calls inside the cluster,
 * as this one crosses the internet.
 *
 * <h2>Failures (backlog #0-93)</h2>
 * Every failure leaves as a {@link NotificationException} carrying the
 * platform's reason; the provider's own error is only its cause. Nothing is
 * logged here: the caller logs it once, at WARN when the reason is permanent
 * (the tenant's to fix) and at ERROR otherwise.
 */
@Component
public class SlackApiClient {

    private static final Logger log = LoggerFactory.getLogger(SlackApiClient.class);

    /** The {@code resilience4j.retry.instances} entry both calls use. */
    public static final String RETRY_NAME = "slack";

    /** Slack's {@code error} codes it documents as worth trying again later. */
    private static final Set<String> TRANSIENT_ERRORS =
            Set.of("internal_error", "fatal_error", "service_unavailable", "request_timeout");

    /** Slack's {@code error} codes for a bot token it no longer accepts (second review of #0-93). */
    private static final Set<String> AUTH_ERRORS = Set.of(
            "invalid_auth", "not_authed", "token_revoked", "token_expired", "account_inactive");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final RetryRegistry retryRegistry;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    // Built from the configurable apiBaseUrl, not a hardcoded constant — lets
    // tests point this class at a local WireMock server instead of the real
    // Slack API. Defaults to the real Slack API via application.yml
    // (notification.channels.slack.api-base-url).
    private final String postMessageUrl;
    private final String updateUrl;

    @Autowired
    public SlackApiClient(RestClient.Builder restClientBuilder,
                          ObjectMapper objectMapper,
                          NotificationChannelProperties properties,
                          RetryRegistry retryRegistry) {
        this(restClientBuilder, HttpClient.newBuilder(), objectMapper, properties, retryRegistry);
    }

    /**
     * @param httpClientBuilder the JDK client to apply the timeouts to; tests
     *                          pass one limited to HTTP/1.1, as WireMock does
     *                          not speak HTTP/2 over plain HTTP
     */
    SlackApiClient(RestClient.Builder restClientBuilder,
                   HttpClient.Builder httpClientBuilder,
                   ObjectMapper objectMapper,
                   NotificationChannelProperties properties,
                   RetryRegistry retryRegistry) {
        final NotificationChannelProperties.Slack slack = properties.channels().slack();
        this.retryRegistry = retryRegistry;
        this.connectTimeout = slack.connectTimeout();
        this.readTimeout = slack.readTimeout();
        // Never follow a redirect (the JDK's default, pinned here, review of #0-103):
        // the request carries the tenant's bot token, which a redirect to another
        // host, from a misconfigured api-base-url or anything in between, would hand
        // over with it. Slack's Web API does not redirect.
        final JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                httpClientBuilder
                        .connectTimeout(slack.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
        requestFactory.setReadTimeout(slack.readTimeout());

        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
        this.postMessageUrl = slack.apiBaseUrl() + "/chat.postMessage";
        this.updateUrl = slack.apiBaseUrl() + "/chat.update";
    }

    /**
     * The longest one call can take, its retries included: every attempt
     * timing out on connect and then on read, with every wait of the retry's
     * backoff between them. {@code NotificationScheduler} checks at startup
     * that a send's worth of these fits between its processing budget and its
     * ShedLock (review of backlog #0-103: with the retry finally running, a
     * Slack that hangs costs this much per call, and an entry started just
     * before the budget ran out could outlive the lock, so that a second
     * replica sent the same notifications again).
     */
    public Duration worstCaseCall() {
        return worstCaseCall(retryRegistry.retry(RETRY_NAME).getRetryConfig(), connectTimeout, readTimeout);
    }

    public static Duration worstCaseCall(RetryConfig retry, Duration connectTimeout, Duration readTimeout) {
        final int attempts = retry.getMaxAttempts();
        Duration total = connectTimeout.plus(readTimeout).multipliedBy(attempts);
        final Either<Throwable, Object> timedOut = Either.left(new IllegalStateException("timed out"));
        for (int attempt = 1; attempt < attempts; attempt++) {
            total = total.plusMillis(retry.<Object>getIntervalBiFunction().apply(attempt, timedOut));
        }
        return total;
    }

    /**
     * Posts {@code message} (its {@code blocks} and {@code text}) to {@code
     * channel}, a channel id or a user id for a DM.
     *
     * @return the posted message's {@code ts}, which {@code chat.update} needs
     * @throws NotificationException when Slack did not post it (after the retries)
     */
    @Retry(name = RETRY_NAME, fallbackMethod = "postMessageFallback")
    public String postMessage(String channel, Map<String, Object> message, String botToken) {
        final Map<String, Object> payload = new LinkedHashMap<>(message);
        payload.put("channel", channel);
        return requireOk(channel, call(postMessageUrl, payload, botToken)).path("ts").asText(null);
    }

    /**
     * Replaces the message {@code ts} in {@code channel} with {@code message}.
     *
     * @throws NotificationException when Slack did not update it (after the retries)
     */
    @Retry(name = RETRY_NAME, fallbackMethod = "updateMessageFallback")
    public void updateMessage(String channel, String ts, Map<String, Object> message, String botToken) {
        final Map<String, Object> payload = new LinkedHashMap<>(message);
        payload.put("channel", channel);
        payload.put("ts", ts);
        requireOk(channel, call(updateUrl, payload, botToken));
    }

    private String call(String url, Map<String, Object> payload, String botToken) {
        return restClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + botToken)
                .body(payload)
                .retrieve()
                .body(String.class);
    }

    String postMessageFallback(String channel, Map<String, Object> message, String botToken,
                               Exception cause) {
        throw failure(channel, cause);
    }

    /**
     * Fixed (backlog #78): the ACK update's fallback used to log and return
     * normally, so {@code SlackActionService} could not tell a failed update
     * from a done one and deleted the tracking row anyway. It throws, like the
     * post's fallback; the caller logs it (backlog #0-103: no longer here).
     */
    void updateMessageFallback(String channel, String ts, Map<String, Object> message, String botToken,
                               Exception cause) {
        throw failure(channel, cause);
    }

    /**
     * The answer of a Slack Web API call, which must say {@code "ok": true}
     * (backlog #0-93). Slack reports most failures (a channel the bot is not
     * in, a revoked token, an archived channel) as HTTP 200 with
     * {@code {"ok":false,"error":"<code>"}}; this used to be read as a
     * delivery, so a message that never left was recorded as SENT. Now it is
     * a {@link NotificationException} carrying Slack's code, which is a fixed
     * vocabulary and safe to record once checked; Slack's free-text fields
     * are not read. Not retried: the retry covers network errors and 5xx, and
     * the answers Slack documents as transient are few.
     */
    private JsonNode requireOk(String channel, String responseBody) {
        final JsonNode answer;
        try {
            answer = responseBody == null ? null : objectMapper.readTree(responseBody);
        } catch (JsonProcessingException e) {
            throw new NotificationException("SLACK", channel,
                    NotificationFailureReason.SLACK_UNAVAILABLE, "unreadable_response", e);
        }
        if (answer == null || !answer.path("ok").isBoolean()) {
            throw new NotificationException("SLACK", channel,
                    NotificationFailureReason.SLACK_UNAVAILABLE, "unreadable_response", null);
        }
        if (!answer.path("ok").asBoolean()) {
            final String code = answer.path("error").asText(null);
            final NotificationException failure =
                    new NotificationException("SLACK", channel, reasonFor(code), code, null);
            if (code != null && failure.detail() == null && log.isDebugEnabled()) {
                // Not a plain code, so not recorded (review: leave a trace for the
                // operator). Shown with anything but printable ASCII replaced and
                // cut, as it came from outside and #0-94 has not escaped log lines.
                log.debug("Slack answered ok:false with an error that is not a plain code: channel={}, error={}",
                        channel, printable(code));
            }
            throw failure;
        }
        return answer;
    }

    private static String printable(String value) {
        final String cut = value.length() > 64 ? value.substring(0, 64) + "..." : value;
        return cut.replaceAll("[^\\x20-\\x7E]", "?");
    }

    private static NotificationFailureReason reasonFor(String slackError) {
        if (slackError == null) {
            // ok:false without saying why (second review: Set.of(...).contains(null)
            // throws, which recorded it as an unexpected error).
            return NotificationFailureReason.SLACK_REJECTED;
        }
        if ("ratelimited".equals(slackError)) {
            return NotificationFailureReason.SLACK_RATE_LIMITED;
        }
        if (AUTH_ERRORS.contains(slackError)) {
            return NotificationFailureReason.SLACK_AUTH_FAILED;
        }
        return TRANSIENT_ERRORS.contains(slackError)
                ? NotificationFailureReason.SLACK_UNAVAILABLE
                : NotificationFailureReason.SLACK_REJECTED;
    }

    /**
     * What a failed Slack call (after its retries) becomes (backlog #0-93).
     * Resilience4j calls the fallback for every exception, not only the
     * retried ones, so a {@link NotificationException} already classified by
     * {@link #requireOk} passes through as it is. An HTTP error is classified
     * by its status alone, never its body; an exception that is not the HTTP
     * client's is rethrown unchanged, so the caller records it by its type.
     */
    static NotificationException failure(String channel, Exception cause) {
        if (cause instanceof NotificationException classified) {
            return classified;
        }
        if (cause instanceof HttpClientErrorException.Unauthorized) {
            return new NotificationException("SLACK", channel,
                    NotificationFailureReason.SLACK_AUTH_FAILED, "http_401", cause);
        }
        if (cause instanceof HttpClientErrorException.TooManyRequests) {
            return new NotificationException("SLACK", channel,
                    NotificationFailureReason.SLACK_RATE_LIMITED, "http_429", cause);
        }
        if (cause instanceof HttpClientErrorException client) {
            return new NotificationException("SLACK", channel, NotificationFailureReason.SLACK_REJECTED,
                    "http_" + client.getStatusCode().value(), cause);
        }
        if (cause instanceof HttpServerErrorException server) {
            return new NotificationException("SLACK", channel, NotificationFailureReason.SLACK_UNAVAILABLE,
                    "http_" + server.getStatusCode().value(), cause);
        }
        if (cause instanceof RestClientException) {
            return new NotificationException("SLACK", channel,
                    NotificationFailureReason.SLACK_UNAVAILABLE, cause);
        }
        if (cause instanceof RuntimeException unexpected) {
            throw unexpected;
        }
        throw new IllegalStateException("Slack call failed", cause);
    }
}
