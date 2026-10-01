package com.incidentplatform.auth.ratelimit;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MfaResetRateLimiter (backlog #0-88)")
class MfaResetRateLimiterTest {

    /** Against a real Redis (same image as docker-compose), as PlatformRateLimiterTest. */
    @Nested
    @Testcontainers
    @DisplayName("token buckets in Redis")
    class Limits {

        @Container
        private static final GenericContainer<?> REDIS =
                new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

        private static RedisClient client;
        private static StatefulRedisConnection<String, byte[]> connection;
        private static ProxyManager<String> proxyManager;

        @BeforeAll
        static void connect() {
            client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
            connection = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
            proxyManager = LettuceBasedProxyManager.builderFor(connection)
                    .withExpirationStrategy(ExpirationAfterWriteStrategy
                            .basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(5)))
                    .build();
        }

        @AfterAll
        static void close() {
            connection.close();
            client.shutdown();
        }

        @BeforeEach
        void flushRedis() {
            try (StatefulRedisConnection<String, String> admin = client.connect()) {
                admin.sync().flushall();
            }
        }

        @Test
        @DisplayName("allows the configured number per admin, then refuses with a Retry-After")
        void limitsPerAdmin() {
            final SimpleMeterRegistry meters = new SimpleMeterRegistry();
            final MfaResetRateLimiter limiter = new MfaResetRateLimiter(proxyManager, 2, 100, meters);
            final UUID admin = UUID.randomUUID();

            assertThat(limiter.tryConsume(admin, "acme").allowed()).isTrue();
            assertThat(limiter.tryConsume(admin, "acme").allowed()).isTrue();
            final RateLimitDecision third = limiter.tryConsume(admin, "acme");

            assertThat(third.outcome()).isEqualTo(RateLimitDecision.Outcome.LIMITED);
            assertThat(third.retryAfterSeconds()).isBetween(1L, 1800L);
            assertThat(rejected(meters, "admin")).isEqualTo(1);
            assertThat(rejected(meters, "tenant")).isZero();
            assertThat(limiter.tryConsume(UUID.randomUUID(), "acme").allowed())
                    .as("another admin of the tenant has a bucket of their own").isTrue();
        }

        @Test
        @DisplayName("the per-tenant limit caps several admins of one tenant together, not other tenants")
        void limitsAdminsOfATenantTogether() {
            final SimpleMeterRegistry meters = new SimpleMeterRegistry();
            final MfaResetRateLimiter limiter = new MfaResetRateLimiter(proxyManager, 2, 3, meters);
            final UUID first = UUID.randomUUID();
            final UUID second = UUID.randomUUID();

            assertThat(limiter.tryConsume(first, "acme").allowed()).isTrue();
            assertThat(limiter.tryConsume(first, "acme").allowed()).isTrue();
            assertThat(limiter.tryConsume(second, "acme").allowed()).isTrue();
            final RateLimitDecision fourth = limiter.tryConsume(second, "acme");

            assertThat(fourth.outcome()).as("second admin within their own limit, the tenant is not")
                    .isEqualTo(RateLimitDecision.Outcome.LIMITED);
            assertThat(rejected(meters, "tenant")).isEqualTo(1);
            assertThat(limiter.tryConsume(UUID.randomUUID(), "globex").allowed())
                    .as("another tenant has its own budget").isTrue();
        }

        @Test
        @DisplayName("an admin over their own limit does not use up the tenant's")
        void refusedAdminLeavesTenantBudget() {
            final MfaResetRateLimiter limiter = new MfaResetRateLimiter(proxyManager, 1, 2, new SimpleMeterRegistry());
            final UUID noisy = UUID.randomUUID();

            assertThat(limiter.tryConsume(noisy, "acme").allowed()).isTrue();
            for (int i = 0; i < 5; i++) {
                assertThat(limiter.tryConsume(noisy, "acme").allowed()).as("retry %d", i).isFalse();
            }

            assertThat(limiter.tryConsume(UUID.randomUUID(), "acme").allowed())
                    .as("the tenant bucket still has its second token").isTrue();
        }

        @Test
        @DisplayName("a refusal by the tenant's bucket still spends the admin's token (documented, the safe direction)")
        void tenantRefusalSpendsAdminToken() {
            final MfaResetRateLimiter limiter = new MfaResetRateLimiter(proxyManager, 2, 2, new SimpleMeterRegistry());
            final UUID first = UUID.randomUUID();
            final UUID second = UUID.randomUUID();
            assertThat(limiter.tryConsume(first, "acme").allowed()).isTrue();
            assertThat(limiter.tryConsume(first, "acme").allowed()).isTrue();

            assertThat(limiter.tryConsume(second, "acme").outcome())
                    .as("the tenant's bucket is empty").isEqualTo(RateLimitDecision.Outcome.LIMITED);
            assertThat(limiter.tryConsume(second, "globex").allowed())
                    .as("second admin's own bucket still had its second token").isTrue();
            assertThat(limiter.tryConsume(second, "globex").outcome())
                    .as("the refused call took the first; no refund").isEqualTo(RateLimitDecision.Outcome.LIMITED);
        }

        @Test
        @DisplayName("refuses at startup a limit below 1, or a tenant limit below the per-admin one")
        void rejectsBadConfiguration() {
            assertThatThrownBy(() -> new MfaResetRateLimiter(proxyManager, 0, 30, new SimpleMeterRegistry()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("per-admin-per-hour");
            assertThatThrownBy(() -> new MfaResetRateLimiter(proxyManager, 10, 9, new SimpleMeterRegistry()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("per-tenant-per-hour");
        }

        private static double rejected(SimpleMeterRegistry meters, String limit) {
            return meters.counter("auth.mfa_reset.ratelimit.rejected", MfaResetRateLimiter.LIMIT_TAG, limit).count();
        }
    }

    /**
     * Fail-closed through the real {@code @CircuitBreaker} proxy, with the
     * {@code mfa-reset-ratelimit} settings of application.yml: a Redis
     * failure comes back as UNAVAILABLE (never ALLOWED, never a 500), and
     * enough failures open the circuit.
     */
    @Nested
    @SpringBootTest(classes = FailClosed.Config.class)
    @ImportAutoConfiguration({AopAutoConfiguration.class, CircuitBreakerAutoConfiguration.class})
    @DisplayName("Redis unavailable")
    class FailClosed {

        /** Not a {@code @Configuration}, so no component scan picks it up. */
        static class Config {
            @Bean
            MeterRegistry meterRegistry() {
                return new SimpleMeterRegistry();
            }

            @Bean
            @SuppressWarnings("unchecked")
            ProxyManager<String> platformRateLimitProxyManager() {
                return mock(ProxyManager.class);
            }

            @Bean
            MfaResetRateLimiter mfaResetRateLimiter(ProxyManager<String> platformRateLimitProxyManager,
                                                    MeterRegistry meterRegistry) {
                return new MfaResetRateLimiter(platformRateLimitProxyManager, 10, 30, meterRegistry);
            }
        }

        @Autowired private MfaResetRateLimiter limiter;
        @Autowired private MeterRegistry meterRegistry;
        @Autowired private ProxyManager<String> proxyManager;
        @Autowired private CircuitBreakerRegistry circuitBreakers;

        @Test
        @DisplayName("Redis down: every reset refused with Retry-After, then the circuit opens and Redis is not tried")
        void redisDown() {
            final CircuitBreaker breaker = circuitBreakers.circuitBreaker("mfa-reset-ratelimit");
            breaker.reset();
            when(proxyManager.builder()).thenThrow(new RedisConnectionException("Redis is down"));

            for (int i = 0; i < 5; i++) {
                final RateLimitDecision decision = limiter.tryConsume(UUID.randomUUID(), "acme");
                assertThat(decision.outcome()).as("call %d", i).isEqualTo(RateLimitDecision.Outcome.UNAVAILABLE);
                assertThat(decision.retryAfterSeconds()).isEqualTo(MfaResetRateLimiter.UNAVAILABLE_RETRY_AFTER_SECONDS);
            }
            assertThat(breaker.getState()).as("after 5 failures (application.yml thresholds)")
                    .isEqualTo(CircuitBreaker.State.OPEN);

            org.mockito.Mockito.clearInvocations(proxyManager);
            assertThat(limiter.tryConsume(UUID.randomUUID(), "acme").outcome())
                    .isEqualTo(RateLimitDecision.Outcome.UNAVAILABLE);
            org.mockito.Mockito.verifyNoInteractions(proxyManager);
            assertThat(meterRegistry.counter("auth.mfa_reset.ratelimit.unavailable").count()).isEqualTo(6);
        }
    }

    @Test
    @DisplayName("a refusal carries its decision; an allowed decision is no refusal")
    void refusedException() {
        final RateLimitDecision limited = new RateLimitDecision(RateLimitDecision.Outcome.LIMITED, 5);

        assertThat(new RateLimitRefusedException(limited).decision()).isEqualTo(limited);
        assertThatThrownBy(() -> new RateLimitRefusedException(RateLimitDecision.ALLOWED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
