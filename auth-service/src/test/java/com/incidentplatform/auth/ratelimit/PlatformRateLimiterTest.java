package com.incidentplatform.auth.ratelimit;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.BeanCreationException;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PlatformRateLimiter (backlog #0-83)")
class PlatformRateLimiterTest {

    /** Against a real Redis (same image as docker-compose), through bucket4j's Lettuce proxy manager. */
    @Nested
    @Testcontainers
    @DisplayName("token bucket in Redis")
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

        @Test
        @DisplayName("allows the configured number per operator, then refuses with a Retry-After")
        void limitsPerOperator() {
            final SimpleMeterRegistry meters = new SimpleMeterRegistry();
            final PlatformRateLimiter limiter = new PlatformRateLimiter(proxyManager, 2, 1000, meters);
            final UUID operator = UUID.randomUUID();

            assertThat(limiter.tryConsume(operator).outcome()).isEqualTo(RateLimitDecision.Outcome.ALLOWED);
            assertThat(limiter.tryConsume(operator).outcome()).isEqualTo(RateLimitDecision.Outcome.ALLOWED);
            final RateLimitDecision third = limiter.tryConsume(operator);

            assertThat(third.outcome()).isEqualTo(RateLimitDecision.Outcome.LIMITED);
            // 2 per hour, refilled gradually: the next one is about 30 minutes away.
            assertThat(third.retryAfterSeconds()).isBetween(1L, 1800L);
            assertThat(rejected(meters, "operator")).isEqualTo(1);
            assertThat(rejected(meters, "global")).isZero();
            assertThat(limiter.tryConsume(UUID.randomUUID()).allowed())
                    .as("another operator has a bucket of its own").isTrue();
        }

        @Test
        @DisplayName("the platform-wide limit caps several operators together")
        void limitsAllOperatorsTogether() {
            final SimpleMeterRegistry meters = new SimpleMeterRegistry();
            final PlatformRateLimiter limiter = new PlatformRateLimiter(proxyManager, 2, 3, meters);
            final UUID first = UUID.randomUUID();
            final UUID second = UUID.randomUUID();

            assertThat(limiter.tryConsume(first).allowed()).isTrue();
            assertThat(limiter.tryConsume(first).allowed()).isTrue();
            assertThat(limiter.tryConsume(second).allowed()).isTrue();
            final RateLimitDecision fourth = limiter.tryConsume(second);

            assertThat(fourth.outcome()).as("second operator is within their own limit, the platform is not")
                    .isEqualTo(RateLimitDecision.Outcome.LIMITED);
            // 3 per hour, refilled gradually: the next one is about 20 minutes away.
            assertThat(fourth.retryAfterSeconds()).isBetween(1L, 1200L);
            assertThat(rejected(meters, "global")).isEqualTo(1);
            assertThat(rejected(meters, "operator")).isZero();
        }

        @Test
        @DisplayName("an operator over their own limit does not use up the platform-wide one")
        void refusedOperatorLeavesGlobalBudget() {
            final PlatformRateLimiter limiter = new PlatformRateLimiter(proxyManager, 1, 2, new SimpleMeterRegistry());
            final UUID noisy = UUID.randomUUID();

            assertThat(limiter.tryConsume(noisy).allowed()).isTrue();
            for (int i = 0; i < 5; i++) {
                assertThat(limiter.tryConsume(noisy).allowed()).as("retry %d", i).isFalse();
            }

            assertThat(limiter.tryConsume(UUID.randomUUID()).allowed())
                    .as("the shared bucket still has its second token").isTrue();
        }

        @Test
        @DisplayName("the limit is shared by every instance, as replicas share Redis")
        void sharedAcrossInstances() {
            final UUID operator = UUID.randomUUID();
            final PlatformRateLimiter first = new PlatformRateLimiter(proxyManager, 1, 1000, new SimpleMeterRegistry());
            final PlatformRateLimiter second = new PlatformRateLimiter(proxyManager, 1, 1000, new SimpleMeterRegistry());

            assertThat(first.tryConsume(operator).allowed()).isTrue();
            assertThat(second.tryConsume(operator).allowed()).isFalse();
        }

        @Test
        @DisplayName("refuses at startup a limit below 1, or a platform-wide limit below the per-operator one")
        void rejectsBadConfiguration() {
            assertThatThrownBy(() -> new PlatformRateLimiter(proxyManager, 0, 50, new SimpleMeterRegistry()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("operations-per-hour");
            assertThatThrownBy(() -> new PlatformRateLimiter(proxyManager, 20, 19, new SimpleMeterRegistry()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("global-operations-per-hour");
        }

        private static double rejected(SimpleMeterRegistry meters, String limit) {
            return meters.counter("platform.ratelimit.rejected", PlatformRateLimiter.LIMIT_TAG, limit).count();
        }

        /**
         * The platform-wide bucket has one fixed key, and bucket4j keeps the
         * configuration a bucket was created with: each test starts empty.
         */
        @BeforeEach
        void flushRedis() {
            try (StatefulRedisConnection<String, String> admin = client.connect()) {
                admin.sync().flushall();
            }
        }
    }

    /**
     * Fail-closed through the real {@code @CircuitBreaker} proxy, with the
     * breaker settings of application.yml (loaded by the test context, not
     * copied, so the two cannot drift): a Redis failure must reach the breaker
     * and come back as UNAVAILABLE, never as ALLOWED and never as an exception
     * (a 500), and enough failures must open the circuit so later calls fail
     * fast without touching Redis.
     */
    @Nested
    @SpringBootTest(classes = FailClosed.Config.class)
    @ImportAutoConfiguration({AopAutoConfiguration.class, CircuitBreakerAutoConfiguration.class})
    @DisplayName("Redis unavailable")
    class FailClosed {

        /**
         * Given to the context by {@code classes} only, and deliberately not a
         * {@code @Configuration}: the application's own component scan (in
         * other {@code @SpringBootTest}s) would otherwise pick it up.
         */
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
            PlatformRateLimiter platformRateLimiter(ProxyManager<String> platformRateLimitProxyManager,
                                                    MeterRegistry meterRegistry) {
                return new PlatformRateLimiter(platformRateLimitProxyManager, 20, 50, meterRegistry);
            }
        }

        @Autowired private PlatformRateLimiter limiter;
        @Autowired private MeterRegistry meterRegistry;
        @Autowired private ProxyManager<String> proxyManager;
        @Autowired private CircuitBreakerRegistry circuitBreakers;

        private CircuitBreaker breaker() {
            return circuitBreakers.circuitBreaker("platform-ratelimit");
        }

        @BeforeEach
        void closedCircuit() {
            breaker().reset();
            org.mockito.Mockito.reset(proxyManager);
        }

        private void refusesFiveTimesThenOpens() {
            for (int i = 0; i < 5; i++) {
                final RateLimitDecision decision = limiter.tryConsume(UUID.randomUUID());
                assertThat(decision.outcome()).as("call %d", i).isEqualTo(RateLimitDecision.Outcome.UNAVAILABLE);
                assertThat(decision.retryAfterSeconds()).isEqualTo(PlatformRateLimiter.UNAVAILABLE_RETRY_AFTER_SECONDS);
            }
            assertThat(breaker().getState()).as("after 5 failures (application.yml thresholds)")
                    .isEqualTo(CircuitBreaker.State.OPEN);

            org.mockito.Mockito.clearInvocations(proxyManager);
            assertThat(limiter.tryConsume(UUID.randomUUID()).outcome())
                    .isEqualTo(RateLimitDecision.Outcome.UNAVAILABLE);
            org.mockito.Mockito.verifyNoInteractions(proxyManager);
        }

        @Test
        @DisplayName("Redis refusing connections: every call refused, then the circuit opens and Redis is not tried")
        void redisDown() {
            when(proxyManager.builder()).thenThrow(new RedisConnectionException("Redis is down"));
            final double before = meterRegistry.counter("platform.ratelimit.unavailable").count();

            refusesFiveTimesThenOpens();

            assertThat(meterRegistry.counter("platform.ratelimit.unavailable").count()).isEqualTo(before + 6);
        }

        @Test
        @DisplayName("the lazy connection failing to be created counts too, so it also opens the circuit")
        void lazyConnectionCannotBeCreated() {
            when(proxyManager.builder()).thenThrow(new BeanCreationException(
                    "platformRateLimitRedisConnection", "connect failed",
                    new RedisConnectionException("Unable to connect")));

            refusesFiveTimesThenOpens();
        }

        /**
         * Every failure opens the circuit, not only a listed few (found in
         * review): bucket4j 8.10 rethrows a Lettuce command timeout as
         * RedisException, but has a TimeoutException of its own, and any other
         * failure means the limit cannot be checked either.
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("otherFailures")
        @DisplayName("any other failure (command timeout, bucket4j's timeout, an unexpected error) opens the circuit too")
        void everyFailureCounts(String name, RuntimeException failure) {
            when(proxyManager.builder()).thenThrow(failure);

            refusesFiveTimesThenOpens();
        }

        static Stream<Arguments> otherFailures() {
            return Stream.of(
                    Arguments.of("Lettuce command timeout",
                            new RedisCommandTimeoutException("Command timed out after 1 second(s)")),
                    Arguments.of("bucket4j timeout",
                            new io.github.bucket4j.TimeoutException("timeout", 1_000_000_000L, 1_000_000_000L)),
                    Arguments.of("unexpected error", new IllegalStateException("unexpected")));
        }
    }
}
