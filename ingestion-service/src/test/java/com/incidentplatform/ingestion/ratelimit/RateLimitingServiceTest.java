package com.incidentplatform.ingestion.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link RateLimitingService} — the Redis-backed rate limiter
 * (see its own Javadoc for why this replaced an in-memory
 * {@code ConcurrentHashMap<String, Bucket>}, and
 * {@code BruteForceProtectionServiceTest} in auth-service (renamed from
 * {@code LoginAttemptServiceTest} — backlog #58) for the sibling test
 * this one's structure mirrors, adapted for bucket4j-redis's
 * {@code ProxyManager} API instead of {@code StringRedisTemplate}).
 *
 * <p>Uses a real {@link SimpleMeterRegistry} rather than mocking every
 * {@code Counter} individually — same choice as
 * {@code BruteForceProtectionServiceTest} — so counter assertions read the
 * actual recorded value instead of verifying mock interactions.
 *
 * <h2>Fixed (backlog #67): RedisFailure/tryConsumeFallback test split</h2>
 * Mirrors the identical split {@code DeduplicationServiceTest} already
 * uses for {@code isDuplicate}/{@code isDuplicateFallback} after that
 * class's own {@code @CircuitBreaker} fix: {@code RedisFailure} below
 * now verifies {@code tryConsume} itself lets a Redis failure propagate
 * (all this plain, proxy-free unit test can verify about the annotated
 * method), and the separate {@code TryConsumeFallback} class calls
 * {@code tryConsumeFallback} directly, since a Mockito-only test has no
 * AOP proxy to invoke it for us the way a real, Spring-managed circuit
 * breaker would.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitingService")
class RateLimitingServiceTest {

    @Mock
    private ProxyManager<String> proxyManager;

    @Mock
    private RemoteBucketBuilder<String> bucketBuilder;

    @Mock
    private BucketProxy tenantBucket;

    @Mock
    private BucketProxy ipBucket;

    private SimpleMeterRegistry meterRegistry;
    private RateLimitingService service;

    private static final String TENANT_ID = "acme-corp";
    private static final String CLIENT_IP = "203.0.113.42";
    private static final String TENANT_KEY = "ratelimit:tenant:" + TENANT_ID;
    private static final String IP_KEY = "ratelimit:ip:" + CLIENT_IP;

    @BeforeEach
    void setUp() {
        final RateLimitingProperties props = buildProperties(true);
        final RateLimitingConfig config = new RateLimitingConfig(props);
        meterRegistry = new SimpleMeterRegistry();

        // proxyManager.builder() is called once per bucket (tenant, then
        // IP) within a single tryConsume() — both calls return the same
        // builder mock, which then routes to a different Bucket mock
        // depending on which key it's asked to build, via the eq(...)
        // matchers in each @Nested class below.
        lenient().when(proxyManager.builder()).thenReturn(bucketBuilder);

        service = new RateLimitingService(config, proxyManager, meterRegistry);
    }

    // Fixed (backlog #73): RateLimitingProperties no longer has a
    // severity component — see that record's own Javadoc for the full
    // account of why the half-built severity-capacity feature was
    // removed rather than completed.
    private RateLimitingProperties buildProperties(boolean enabled) {
        return new RateLimitingProperties(
                enabled,
                new RateLimitingProperties.Tenant(100, 10, 1),
                new RateLimitingProperties.Ip(50, 5, 1)
        );
    }

    private double counterValue(String name) {
        return meterRegistry.get(name).counter().count();
    }

    // ── disabled mode ────────────────────────────────────────────────────

    @Nested
    @DisplayName("disabled mode")
    class DisabledMode {

        @Test
        @DisplayName("always permits and never touches Redis when rate-limiting.enabled=false")
        void alwaysPermitsWhenDisabled() {
            final RateLimitingConfig disabledConfig =
                    new RateLimitingConfig(buildProperties(false));
            final RateLimitingService disabledService = new RateLimitingService(
                    disabledConfig, proxyManager, new SimpleMeterRegistry());

            final RateLimitResult result = disabledService.tryConsume(TENANT_ID, CLIENT_IP);

            assertThat(result.allowed()).isTrue();
            verify(proxyManager, never()).builder();
        }
    }

    // ── tenant bucket ────────────────────────────────────────────────────

    @Nested
    @DisplayName("tenant bucket")
    class TenantBucket {

        @Test
        @DisplayName("rejects and increments the tenant counter when the tenant bucket is exhausted")
        void rejectsWhenTenantBucketExhausted() {
            given(bucketBuilder.build(eq(TENANT_KEY), any(Supplier.class)))
                    .willReturn(tenantBucket);
            given(tenantBucket.tryConsume(1)).willReturn(false);

            final RateLimitResult result = service.tryConsume(TENANT_ID, CLIENT_IP);

            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains(TENANT_ID);
            assertThat(counterValue("rate_limit.tenant.rejected")).isEqualTo(1.0);

            // Short-circuits — never even builds the IP bucket once the
            // tenant limit alone is enough to reject.
            verify(bucketBuilder, never()).build(eq(IP_KEY), any(Supplier.class));
        }

        @Test
        @DisplayName("proceeds to the IP bucket when the tenant bucket allows the request")
        void proceedsToIpBucketWhenTenantAllows() {
            given(bucketBuilder.build(eq(TENANT_KEY), any(Supplier.class)))
                    .willReturn(tenantBucket);
            given(tenantBucket.tryConsume(1)).willReturn(true);
            given(bucketBuilder.build(eq(IP_KEY), any(Supplier.class)))
                    .willReturn(ipBucket);
            given(ipBucket.tryConsume(1)).willReturn(true);

            final RateLimitResult result = service.tryConsume(TENANT_ID, CLIENT_IP);

            assertThat(result.allowed()).isTrue();
        }
    }

    // ── IP bucket ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("IP bucket")
    class IpBucketTests {

        @Test
        @DisplayName("rejects and increments the IP counter when the IP bucket is exhausted")
        void rejectsWhenIpBucketExhausted() {
            given(bucketBuilder.build(eq(TENANT_KEY), any(Supplier.class)))
                    .willReturn(tenantBucket);
            given(tenantBucket.tryConsume(1)).willReturn(true);
            given(bucketBuilder.build(eq(IP_KEY), any(Supplier.class)))
                    .willReturn(ipBucket);
            given(ipBucket.tryConsume(1)).willReturn(false);

            final RateLimitResult result = service.tryConsume(TENANT_ID, CLIENT_IP);

            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains(CLIENT_IP);
            assertThat(counterValue("rate_limit.ip.rejected")).isEqualTo(1.0);
        }
    }

    // ── both buckets allow ───────────────────────────────────────────────

    @Test
    @DisplayName("permits when both the tenant and IP buckets have capacity")
    void permitsWhenBothBucketsHaveCapacity() {
        given(bucketBuilder.build(eq(TENANT_KEY), any(Supplier.class)))
                .willReturn(tenantBucket);
        given(tenantBucket.tryConsume(1)).willReturn(true);
        given(bucketBuilder.build(eq(IP_KEY), any(Supplier.class)))
                .willReturn(ipBucket);
        given(ipBucket.tryConsume(1)).willReturn(true);

        final RateLimitResult result = service.tryConsume(TENANT_ID, CLIENT_IP);

        assertThat(result.allowed()).isTrue();
        assertThat(result.reason()).isNull();
    }

    // ── Redis failure — propagates for the @CircuitBreaker proxy ────────
    //
    // Fixed (backlog #67): previously tryConsume() caught Redis
    // exceptions internally and returned a permit directly — see
    // RateLimitingService's own Javadoc for why that defeated
    // @CircuitBreaker exactly the way DeduplicationServiceTest's
    // identical fix already documented for isDuplicate(). This class is
    // a plain unit test (no Spring context, no AOP proxy involved), so
    // it can only verify the annotated method's own behavior in
    // isolation: that a Redis failure now propagates instead of being
    // swallowed. The actual fail-open behavior is tested separately and
    // directly against tryConsumeFallback below — that's the method the
    // proxy would call in a real, Spring-managed circuit breaker.

    @Nested
    @DisplayName("Redis failure")
    class RedisFailure {

        @Test
        @DisplayName("propagates a Redis failure instead of swallowing it — " +
                "this is what makes the @CircuitBreaker proxy actually work")
        void propagatesRedisFailure() {
            given(bucketBuilder.build(eq(TENANT_KEY), any(Supplier.class)))
                    .willThrow(new RuntimeException("Redis connection refused"));

            assertThatThrownBy(() -> service.tryConsume(TENANT_ID, CLIENT_IP))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Redis connection refused");
        }

        @Test
        @DisplayName("propagates even when only the IP bucket call fails")
        void propagatesWhenIpBucketCallFails() {
            given(bucketBuilder.build(eq(TENANT_KEY), any(Supplier.class)))
                    .willReturn(tenantBucket);
            given(tenantBucket.tryConsume(1)).willReturn(true);
            given(bucketBuilder.build(eq(IP_KEY), any(Supplier.class)))
                    .willThrow(new RuntimeException("Redis timeout"));

            assertThatThrownBy(() -> service.tryConsume(TENANT_ID, CLIENT_IP))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Redis timeout");
        }
    }

    // ── tryConsumeFallback ───────────────────────────────────────────────

    /**
     * The actual regression coverage for backlog #67's fail-open
     * contract — called directly, exactly as
     * {@code DeduplicationServiceTest.IsDuplicateFallback} tests
     * {@code isDuplicateFallback}, since a plain Mockito unit test has
     * no AOP proxy to invoke it for us.
     */
    @Nested
    @DisplayName("tryConsumeFallback")
    class TryConsumeFallback {

        @Test
        @DisplayName("fails open (permits) and increments the Redis-error counter")
        void failsOpenAndIncrementsCounter() {
            final RateLimitResult result = service.tryConsumeFallback(
                    TENANT_ID, CLIENT_IP, new RuntimeException("Redis unavailable"));

            assertThat(result.allowed()).isTrue();
            assertThat(counterValue("rate_limit.redis.errors")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("does not touch the tenant or IP rejection counters — this is not a real rejection")
        void doesNotAffectRejectionCounters() {
            service.tryConsumeFallback(
                    TENANT_ID, CLIENT_IP, new RuntimeException("boom"));

            assertThat(counterValue("rate_limit.tenant.rejected")).isEqualTo(0.0);
            assertThat(counterValue("rate_limit.ip.rejected")).isEqualTo(0.0);
        }
    }
}