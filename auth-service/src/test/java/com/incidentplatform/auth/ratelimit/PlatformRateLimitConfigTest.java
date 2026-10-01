package com.incidentplatform.auth.ratelimit;

import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limiter's Redis connection reaches the same Redis, the same way, as
 * {@code spring.data.redis.*} says (backlog #0-83). Found in review: ACL user,
 * TLS, database and connect timeout were ignored, which would make every
 * platform API write a permanent 503 against such a Redis.
 */
@DisplayName("PlatformRateLimitConfig")
class PlatformRateLimitConfigTest {

    @Test
    @DisplayName("maps host, port, database, TLS, ACL user, password and both timeouts")
    void mapsEverySetting() {
        final RedisProperties properties = new RedisProperties();
        properties.setHost("redis.internal");
        properties.setPort(6380);
        properties.setDatabase(3);
        properties.getSsl().setEnabled(true);
        properties.setUsername("ratelimit");
        properties.setPassword("s3cret");
        properties.setTimeout(Duration.ofMillis(800));
        properties.setConnectTimeout(Duration.ofSeconds(3));

        final RedisURI uri = PlatformRateLimitConfig.redisUri(properties);

        assertThat(uri.getHost()).isEqualTo("redis.internal");
        assertThat(uri.getPort()).isEqualTo(6380);
        assertThat(uri.getDatabase()).isEqualTo(3);
        assertThat(uri.isSsl()).isTrue();
        assertThat(uri.getUsername()).isEqualTo("ratelimit");
        assertThat(new String(uri.getPassword())).isEqualTo("s3cret");
        assertThat(uri.getTimeout()).isEqualTo(Duration.ofMillis(800));
        assertThat(PlatformRateLimitConfig.clientOptions(properties).getSocketOptions().getConnectTimeout())
                .isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("defaults: no auth, plain TCP, database 0, 1 s command and 2 s connect timeout")
    void defaults() {
        final RedisProperties properties = new RedisProperties();
        properties.setTimeout(null);
        properties.setConnectTimeout(null);

        final RedisURI uri = PlatformRateLimitConfig.redisUri(properties);

        assertThat(uri.getUsername()).isNull();
        assertThat(uri.getPassword()).isNull();
        assertThat(uri.isSsl()).isFalse();
        assertThat(uri.getDatabase()).isZero();
        assertThat(uri.getTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(PlatformRateLimitConfig.clientOptions(properties).getSocketOptions().getConnectTimeout())
                .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("a password without a user authenticates as the default user")
    void passwordOnly() {
        final RedisProperties properties = new RedisProperties();
        properties.setPassword("s3cret");

        final RedisURI uri = PlatformRateLimitConfig.redisUri(properties);

        assertThat(uri.getUsername()).isNull();
        assertThat(new String(uri.getPassword())).isEqualTo("s3cret");
    }
}
