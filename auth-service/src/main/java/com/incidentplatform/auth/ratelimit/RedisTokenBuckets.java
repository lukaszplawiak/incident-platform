package com.incidentplatform.auth.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;

import java.time.Duration;

/**
 * The token-bucket steps shared by the fail-closed limiters (backlog #0-83,
 * #0-88): an hourly bucket, taking one token from a bucket in Redis, and the
 * Retry-After a refusal reports. Plain static helpers, so each limiter keeps
 * its own {@code @CircuitBreaker} method and its own metrics.
 */
final class RedisTokenBuckets {

    private RedisTokenBuckets() {
    }

    /** {@code operations} per hour, refilled gradually. */
    static BucketConfiguration perHour(long operations) {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(operations)
                        .refillGreedy(operations, Duration.ofHours(1))
                        .build())
                .build();
    }

    /** Takes one token; a Redis failure is thrown, for the caller's circuit breaker. */
    static ConsumptionProbe consume(ProxyManager<String> proxyManager, String key, BucketConfiguration configuration) {
        return proxyManager.builder()
                .build(key, () -> configuration)
                .tryConsumeAndReturnRemaining(1);
    }

    /** Seconds until the refused bucket has a token again, at least 1. */
    static long retryAfterSeconds(ConsumptionProbe refused) {
        return Math.max(1, Duration.ofNanos(refused.getNanosToWaitForRefill()).toSeconds());
    }
}
