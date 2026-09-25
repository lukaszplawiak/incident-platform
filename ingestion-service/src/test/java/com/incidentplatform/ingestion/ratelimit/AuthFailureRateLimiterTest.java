package com.incidentplatform.ingestion.ratelimit;

import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

/**
 * Tests for {@link AuthFailureRateLimiter}, in the style of
 * {@link RateLimitingServiceTest}: no AOP proxy here, so Redis failures are
 * shown propagating from the annotated methods and the fail-open fallbacks
 * are called directly.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthFailureRateLimiter")
class AuthFailureRateLimiterTest {

    private static final String CLIENT_IP = "203.0.113.9";
    private static final String KEY = "ratelimit:authfail:ip:" + CLIENT_IP;

    @Mock
    private ProxyManager<String> proxyManager;

    @Mock
    private RemoteBucketBuilder<String> bucketBuilder;

    @Mock
    private BucketProxy bucket;

    private SimpleMeterRegistry meterRegistry;
    private AuthFailureRateLimiter limiter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        lenient().when(proxyManager.builder()).thenReturn(bucketBuilder);
        lenient().when(bucketBuilder.build(eq(KEY), any(java.util.function.Supplier.class)))
                .thenReturn(bucket);
        limiter = new AuthFailureRateLimiter(proxyManager,
                new AuthFailureRateLimitProperties(10, 10, 60), meterRegistry);
    }

    @Test
    @DisplayName("not blocked while failures remain, and checking consumes nothing")
    void notBlockedWithTokensLeft() {
        given(bucket.getAvailableTokens()).willReturn(3L);

        assertThat(limiter.isBlocked(CLIENT_IP)).isFalse();
        then(bucket).should(never()).tryConsume(1);
    }

    @Test
    @DisplayName("blocked once the IP's failures are used up, and counted")
    void blockedWhenExhausted() {
        given(bucket.getAvailableTokens()).willReturn(0L);

        assertThat(limiter.isBlocked(CLIENT_IP)).isTrue();
        assertThat(meterRegistry.get("rate_limit.auth_failure.ip.rejected").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a failure consumes one token from the IP's bucket")
    void recordFailureConsumes() {
        limiter.recordFailure(CLIENT_IP);

        then(bucket).should().tryConsume(1);
    }

    @Test
    @DisplayName("retryAfter is one refill period")
    void retryAfterIsRefillPeriod() {
        assertThat(limiter.retryAfter()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("a Redis failure propagates from the annotated method (the proxy routes it to the fallback)")
    void redisFailurePropagates() {
        given(bucket.getAvailableTokens()).willThrow(new RuntimeException("Redis unavailable"));

        assertThatThrownBy(() -> limiter.isBlocked(CLIENT_IP)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("fallbacks fail open: nothing is blocked, and the Redis error is counted")
    void fallbacksFailOpen() {
        final RuntimeException boom = new RuntimeException("boom");

        assertThat(limiter.isBlockedFallback(CLIENT_IP, boom)).isFalse();
        limiter.recordFailureFallback(CLIENT_IP, boom);

        assertThat(meterRegistry.get("rate_limit.redis.errors").counter().count()).isEqualTo(2.0);
    }
}
