package com.incidentplatform.ingestion.ratelimit;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import java.time.Duration;

/**
 * Wires up {@code bucket4j-redis} as the backing store for
 * {@link RateLimitingService}, replacing an in-memory
 * {@code ConcurrentHashMap<String, Bucket>} that never evicted entries.
 *
 * <h2>Why this was a real problem, not just untidy code</h2>
 * The old map was keyed partly by client IP, read from the fully
 * caller-controlled {@code X-Forwarded-For} header — an attacker could
 * grow the map without bound simply by sending many distinct values,
 * turning the rate limiter itself into a memory-exhaustion vector.
 * Separately, that state was per-JVM: with {@code ingestion-service}
 * scaled to multiple replicas, each pod counted independently, so the
 * effective limit became {@code configured_limit × replica_count} —
 * silently weaker exactly when traffic (and therefore replica count) is
 * highest.
 *
 * <h2>Why a raw Lettuce connection, not Spring Data Redis</h2>
 * bucket4j-redis's own maintainers removed Spring Data Redis integration
 * ("infinite source of problems" — bucket4j GitHub discussion #322) in
 * favor of connecting directly against the underlying client library.
 * This class builds its own {@link RedisClient} rather than reusing the
 * {@code LettuceConnectionFactory} Spring Boot auto-configures for
 * {@code StringRedisTemplate} elsewhere in this service (used by
 * {@link com.incidentplatform.ingestion.service.DeduplicationService}) —
 * connection details still come from the same {@code spring.data.redis.*}
 * properties via Spring Boot's own {@link RedisProperties}, so nothing is
 * duplicated or reconfigured separately.
 *
 * <h2>Self-expiring keys — this is what fixes the memory leak</h2>
 * {@link ExpirationAfterWriteStrategy#basedOnTimeForRefillingBucketUpToMax}
 * tells Redis to expire each bucket's key automatically once it would
 * have refilled to full capacity anyway — an inactive tenant/IP's bucket
 * disappears from Redis on its own. No manual eviction logic anywhere,
 * matching how {@code DeduplicationService}'s keys already expire via
 * {@code SETEX} in this same module.
 */
@Configuration
public class RedisRateLimitConfig {

    private static final RedisCodec<String, byte[]> CODEC =
            RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

    @Bean(destroyMethod = "shutdown")
    public RedisClient rateLimitRedisClient(RedisProperties redisProperties) {
        final RedisURI.Builder uriBuilder = RedisURI.Builder
                .redis(redisProperties.getHost(), redisProperties.getPort())
                .withTimeout(redisProperties.getTimeout() != null
                        ? redisProperties.getTimeout() : Duration.ofSeconds(1));

        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
            uriBuilder.withPassword(redisProperties.getPassword().toCharArray());
        }

        return RedisClient.create(uriBuilder.build());
    }

    @Bean(destroyMethod = "close")
    @DependsOn("rateLimitRedisClient")
    public StatefulRedisConnection<String, byte[]> rateLimitRedisConnection(
            RedisClient rateLimitRedisClient) {
        return rateLimitRedisClient.connect(CODEC);
    }

    /**
     * {@code ProxyManager<String>} — the bucket4j-redis entry point.
     * {@link RateLimitingService} calls
     * {@code proxyManager.builder().build(key, configuration)} per
     * request instead of caching {@code Bucket} instances itself; each
     * call is a Redis round-trip (same trade-off
     * {@code DeduplicationService} and auth-service's
     * {@code LoginAttemptService} already accept elsewhere in this
     * codebase), and per-key state now lives in Redis, not JVM heap.
     */
    @Bean
    public ProxyManager<String> rateLimitProxyManager(
            StatefulRedisConnection<String, byte[]> rateLimitRedisConnection) {
        return LettuceBasedProxyManager.builderFor(rateLimitRedisConnection)
                .withExpirationStrategy(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                                Duration.ofMinutes(10)))
                .build();
    }
}