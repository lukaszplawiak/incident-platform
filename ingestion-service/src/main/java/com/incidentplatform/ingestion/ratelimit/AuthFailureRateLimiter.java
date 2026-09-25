package com.incidentplatform.ingestion.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Per-IP limit on failed API key authentications, applied <em>before</em> a key
 * is sent to auth-service (backlog #0-16).
 *
 * <h2>Why before, and why only failures</h2>
 * The existing {@link RateLimitingService} runs in the controller, after
 * authentication, keyed by tenant — an unauthenticated request never reaches
 * it. With introspection, every unknown key is an HTTP call to auth-service,
 * so without this a stream of random keys from one client becomes a stream of
 * auth-service calls. Guessing a 192-bit key is infeasible; the concern is that
 * amplification, not brute force. Only failures consume from the bucket: a
 * valid key is never slowed down, and a key already in the positive cache is
 * let through before this limiter is consulted at all
 * ({@code RemoteApiKeyLookupService}), so another client behind the same NAT
 * cannot lock out a working integration.
 *
 * <h2>Fail open, like the other limiters</h2>
 * Redis-backed through the same {@link ProxyManager} and circuit breaker
 * ({@code redis-ratelimit}) as {@link RateLimitingService} (backlog #67): if
 * Redis is down, nothing is blocked and failures are not counted, and
 * {@code rate_limit.redis.errors} records it. A limiter must not become the
 * reason alerts are rejected.
 */
@Service
@EnableConfigurationProperties(AuthFailureRateLimitProperties.class)
public class AuthFailureRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AuthFailureRateLimiter.class);

    private static final String KEY_PREFIX = "ratelimit:authfail:ip:";

    private final ProxyManager<String> proxyManager;
    private final BucketConfiguration bucketConfiguration;
    private final Duration refillPeriod;
    private final Counter blockedCounter;
    private final Counter redisErrorCounter;

    public AuthFailureRateLimiter(ProxyManager<String> proxyManager,
                                  AuthFailureRateLimitProperties properties,
                                  MeterRegistry meterRegistry) {
        this.proxyManager = proxyManager;
        this.refillPeriod = Duration.ofSeconds(properties.refillPeriodSeconds());
        this.bucketConfiguration = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(properties.capacity())
                        .refillGreedy(properties.refillTokens(), refillPeriod)
                        .build())
                .build();
        this.blockedCounter = Counter.builder("rate_limit.auth_failure.ip.rejected")
                .description("API key requests rejected because the client IP had too many failed authentications")
                .register(meterRegistry);
        // Same meter as RateLimitingService's: both count Redis failures of the
        // one Redis instance that backs every limiter in this service.
        this.redisErrorCounter = Counter.builder("rate_limit.redis.errors")
                .description("Number of Redis errors during rate limit checks — " +
                        "requests are allowed through when this happens (fail open)")
                .register(meterRegistry);
    }

    /** Whether this IP has used up its failed attempts. Consumes nothing. */
    @CircuitBreaker(name = "redis-ratelimit", fallbackMethod = "isBlockedFallback")
    public boolean isBlocked(String clientIp) {
        final boolean blocked = proxyManager.builder()
                .build(KEY_PREFIX + clientIp, () -> bucketConfiguration)
                .getAvailableTokens() <= 0;
        if (blocked) {
            blockedCounter.increment();
            log.warn("Too many failed API key authentications from clientIp={}", clientIp);
        }
        return blocked;
    }

    /** Counts one failed authentication for this IP. */
    @CircuitBreaker(name = "redis-ratelimit", fallbackMethod = "recordFailureFallback")
    public void recordFailure(String clientIp) {
        proxyManager.builder()
                .build(KEY_PREFIX + clientIp, () -> bucketConfiguration)
                .tryConsume(1);
    }

    /** How long a blocked client should wait: one refill period. */
    public Duration retryAfter() {
        return refillPeriod;
    }

    @SuppressWarnings("unused")
    boolean isBlockedFallback(String clientIp, Exception e) {
        redisErrorCounter.increment();
        log.error("Redis unavailable during auth-failure limit check — failing open: " +
                "clientIp={}, error={}", clientIp, e.getMessage(), e);
        return false;
    }

    @SuppressWarnings("unused")
    void recordFailureFallback(String clientIp, Exception e) {
        redisErrorCounter.increment();
        log.error("Redis unavailable while counting a failed authentication — not counted: " +
                "clientIp={}, error={}", clientIp, e.getMessage(), e);
    }
}
