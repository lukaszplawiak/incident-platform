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
 * {@code LoginAttemptServiceTest} in auth-service for the sibling test
 * this one's structure mirrors, adapted for bucket4j-redis's
 * {@code ProxyManager} API instead of {@code StringRedisTemplate}).
 *
 * <p>Uses a real {@link SimpleMeterRegistry} rather than mocking every
 * {@code Counter} individually — same choice as
 * {@code LoginAttemptServiceTest} — so counter assertions read the
 * actual recorded value instead of verifying mock interactions.
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

    private RateLimitingProperties buildProperties(boolean enabled) {
        return new RateLimitingProperties(
                enabled,
                new RateLimitingProperties.Tenant(100, 10, 1),
                new RateLimitingProperties.Ip(50, 5, 1),
                new RateLimitingProperties.Severity(
                        new RateLimitingProperties.SeverityLimit(1000),
                        new RateLimitingProperties.SeverityLimit(500),
                        new RateLimitingProperties.SeverityLimit(100),
                        new RateLimitingProperties.SeverityLimit(50)
                )
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

    // ── Redis failure — fail open ────────────────────────────────────────
    //
    // Same contract as DeduplicationService (this module) and
    // LoginAttemptService (auth-service): a Redis outage must not take
    // the ingestion pipeline down with it.

    @Nested
    @DisplayName("Redis failure")
    class RedisFailure {

        @Test
        @DisplayName("fails open (permits) and increments the Redis-error counter when Redis is unavailable")
        void failsOpenOnRedisException() {
            given(bucketBuilder.build(eq(TENANT_KEY), any(Supplier.class)))
                    .willThrow(new RuntimeException("Redis connection refused"));

            final RateLimitResult result = service.tryConsume(TENANT_ID, CLIENT_IP);

            assertThat(result.allowed()).isTrue();
            assertThat(counterValue("rate_limit.redis.errors")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("fails open even when only the IP bucket call fails")
        void failsOpenWhenIpBucketCallFails() {
            given(bucketBuilder.build(eq(TENANT_KEY), any(Supplier.class)))
                    .willReturn(tenantBucket);
            given(tenantBucket.tryConsume(1)).willReturn(true);
            given(bucketBuilder.build(eq(IP_KEY), any(Supplier.class)))
                    .willThrow(new RuntimeException("Redis timeout"));

            final RateLimitResult result = service.tryConsume(TENANT_ID, CLIENT_IP);

            assertThat(result.allowed()).isTrue();
            assertThat(counterValue("rate_limit.redis.errors")).isEqualTo(1.0);
        }
    }
}