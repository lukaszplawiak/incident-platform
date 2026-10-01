package com.incidentplatform.auth.ratelimit;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;

/**
 * bucket4j's Redis state for {@link PlatformRateLimiter} (backlog #0-83). The
 * same setup as ingestion-service's {@code RedisRateLimitConfig} (#67): a Lettuce
 * connection of its own with a byte-array codec, which bucket4j's
 * {@code LettuceBasedProxyManager} needs, to the Redis that
 * {@code spring.data.redis.*} names. Kept per service on purpose: {@code shared}
 * does not depend on bucket4j, and only these two services rate-limit with it.
 *
 * <p>The connection and the proxy manager are {@link Lazy}: they are created on
 * the first platform API write, not at startup. Connecting eagerly would make
 * auth-service, and with it every login, fail to start while Redis is down,
 * for the sake of a rarely used operator API. A failed creation is retried on
 * the next call and, meanwhile, refuses the operation (fail-closed).
 */
@Configuration
public class PlatformRateLimitConfig {

    private static final RedisCodec<String, byte[]> CODEC =
            RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

    /**
     * Every connection setting of {@code spring.data.redis.*} that applies to
     * one Redis node, so this connection reaches the same Redis the same way
     * as Spring Data Redis does (found in review: ACL user, TLS, database and
     * connect timeout were ignored, which would turn a Redis with any of them
     * into a permanent 503 here). Single node only, as everywhere in the
     * platform (compose and k8s run one Redis): {@code spring.data.redis.url},
     * Sentinel and Cluster are not read, and a move to any of them needs this
     * method (and ingestion-service's twin) extended, or the limiter fails
     * closed.
     */
    @Bean(destroyMethod = "shutdown")
    public RedisClient platformRateLimitRedisClient(RedisProperties redisProperties) {
        final RedisClient client = RedisClient.create(redisUri(redisProperties));
        client.setOptions(clientOptions(redisProperties));
        return client;
    }

    static RedisURI redisUri(RedisProperties redisProperties) {
        final RedisURI.Builder uriBuilder = RedisURI.Builder
                .redis(redisProperties.getHost(), redisProperties.getPort())
                .withDatabase(redisProperties.getDatabase())
                .withSsl(redisProperties.getSsl().isEnabled())
                .withTimeout(redisProperties.getTimeout() != null
                        ? redisProperties.getTimeout() : Duration.ofSeconds(1));
        final String password = redisProperties.getPassword();
        if (password != null && !password.isBlank()) {
            final String username = redisProperties.getUsername();
            if (username != null && !username.isBlank()) {
                uriBuilder.withAuthentication(username, password.toCharArray());
            } else {
                uriBuilder.withPassword(password.toCharArray());
            }
        }
        return uriBuilder.build();
    }

    static ClientOptions clientOptions(RedisProperties redisProperties) {
        return ClientOptions.builder()
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(redisProperties.getConnectTimeout() != null
                                ? redisProperties.getConnectTimeout() : Duration.ofSeconds(2))
                        .build())
                .build();
    }

    @Bean(destroyMethod = "close")
    @Lazy
    @DependsOn("platformRateLimitRedisClient")
    public StatefulRedisConnection<String, byte[]> platformRateLimitRedisConnection(
            RedisClient platformRateLimitRedisClient) {
        return platformRateLimitRedisClient.connect(CODEC);
    }

    /** Buckets expire once they would be full again, so idle operators leave no keys behind. */
    @Bean
    @Lazy
    public ProxyManager<String> platformRateLimitProxyManager(
            StatefulRedisConnection<String, byte[]> platformRateLimitRedisConnection) {
        return LettuceBasedProxyManager.builderFor(platformRateLimitRedisConnection)
                .withExpirationStrategy(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                                Duration.ofMinutes(5)))
                .build();
    }
}
