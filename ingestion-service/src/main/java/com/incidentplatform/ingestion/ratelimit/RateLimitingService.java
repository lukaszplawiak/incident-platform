package com.incidentplatform.ingestion.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Per-tenant and per-IP rate limiter backed by bucket4j token buckets.
 *
 * <h2>Fixed: unbounded in-memory maps (real memory-leak / DoS risk)</h2>
 * Previously this class cached {@code Bucket} instances forever in two
 * plain {@code ConcurrentHashMap}s — one entry per tenant, one per
 * client IP, never evicted. {@code clientIp} comes from the
 * {@code X-Forwarded-For} header, which a caller fully controls, so an
 * attacker could send arbitrarily many distinct values to grow the IP
 * map without bound — the rate limiter itself becoming a memory-
 * exhaustion vector. Separately, that in-memory state was also per-pod:
 * with {@code ingestion-service} scaled to multiple replicas (its own
 * HPA allows up to 3), the effective limit became
 * {@code configured_limit × replica_count}, silently weaker exactly
 * when traffic (and therefore replica count) is highest.
 *
 * <p>Both problems are fixed the same way {@code DeduplicationService}
 * (same module) and {@code BruteForceProtectionService} (auth-service,
 * renamed from {@code LoginAttemptService} — backlog #58) already
 * solve the identical class of problem: move the per-key state to
 * Redis, which expires it automatically and shares it across every
 * replica. See {@link RedisRateLimitConfig} for the Redis wiring and
 * {@link RateLimitingConfig} for why it now hands out reusable
 * {@code BucketConfiguration} rules instead of per-key {@code Bucket}
 * instances.
 *
 * <h2>Fixed (backlog #67): no @CircuitBreaker on the Redis dependency</h2>
 * {@code DeduplicationService} (this same module, the identical class
 * of Redis dependency, the identical fail-open philosophy) protects its
 * Redis call with {@code @CircuitBreaker} — this class only had a plain
 * {@code try/catch}, no circuit breaker. The two behave identically
 * during a full Redis outage (both fail open), but differ during
 * degradation (elevated latency/timeouts rather than immediate
 * connection failure): without a circuit breaker, every single
 * {@code tryConsume} call — one per incoming alert-ingestion HTTP
 * request — independently waits out its own Redis timeout before
 * falling back, rather than the circuit opening after a threshold of
 * failures and immediately failing open for subsequent calls with no
 * further wait on Redis at all. Under sustained degradation with many
 * concurrent requests, that difference can exhaust the HTTP
 * request-handling thread pool — a secondary availability problem on
 * top of the Redis issue itself, which the circuit breaker is
 * specifically designed to prevent.
 *
 * <p>Fixed the same way {@code DeduplicationService} already fixed the
 * identical "internal try/catch defeats the @CircuitBreaker proxy"
 * issue for itself (see that class's own Javadoc for the full
 * mechanism): the try/catch here is removed so a Redis failure
 * propagates out of {@link #tryConsume} for the proxy to see and
 * record, and {@link #tryConsumeFallback} — called directly by the
 * proxy once the failure-rate threshold is crossed — now provides the
 * fail-open behavior instead. See {@code application.yml}'s
 * {@code resilience4j.circuitbreaker.instances.redis-ratelimit} for the
 * threshold configuration, deliberately identical to
 * {@code redis-dedup}'s — both protect the same Redis instance in the
 * same service.
 *
 * <p>The {@code enabled} flag is read from {@link RateLimitingProperties}
 * injected through {@link RateLimitingConfig} — no {@code @Value} annotation
 * needed. This keeps all rate-limiting configuration in one place and makes
 * it trivial to disable in tests by setting {@code rate-limiting.enabled=false}.
 */
@Service
public class RateLimitingService {

    private static final Logger log =
            LoggerFactory.getLogger(RateLimitingService.class);

    private static final String TENANT_KEY_PREFIX = "ratelimit:tenant:";
    private static final String IP_KEY_PREFIX = "ratelimit:ip:";

    private final RateLimitingConfig config;
    private final ProxyManager<String> proxyManager;
    private final boolean enabled;

    private final Counter tenantRateLimitedCounter;
    private final Counter ipRateLimitedCounter;
    private final Counter redisErrorCounter;

    public RateLimitingService(RateLimitingConfig config,
                               ProxyManager<String> proxyManager,
                               MeterRegistry meterRegistry) {
        this.config = config;
        this.proxyManager = proxyManager;
        this.enabled = config.properties().enabled();

        this.tenantRateLimitedCounter = Counter.builder("rate_limit.tenant.rejected")
                .description("Number of requests rejected by tenant rate limiter")
                .register(meterRegistry);

        this.ipRateLimitedCounter = Counter.builder("rate_limit.ip.rejected")
                .description("Number of requests rejected by IP rate limiter")
                .register(meterRegistry);

        this.redisErrorCounter = Counter.builder("rate_limit.redis.errors")
                .description("Number of Redis errors during rate limit checks — " +
                        "requests are allowed through when this happens (fail open)")
                .register(meterRegistry);
    }

    @CircuitBreaker(name = "redis-ratelimit", fallbackMethod = "tryConsumeFallback")
    public RateLimitResult tryConsume(String tenantId, String clientIp) {
        if (!enabled) {
            return RateLimitResult.permit();
        }

        // No try/catch here — a Redis failure must propagate out of this
        // method for @CircuitBreaker's proxy to see it and record it.
        // See this class's Javadoc.
        final Bucket tenantBucket = proxyManager.builder()
                .build(TENANT_KEY_PREFIX + tenantId, config::tenantBucketConfiguration);

        if (!tenantBucket.tryConsume(1)) {
            tenantRateLimitedCounter.increment();
            log.warn("Rate limit exceeded for tenant: tenantId={}, clientIp={}",
                    tenantId, clientIp);
            return RateLimitResult.tenantLimited(tenantId);
        }

        final Bucket ipBucket = proxyManager.builder()
                .build(IP_KEY_PREFIX + clientIp, config::ipBucketConfiguration);

        if (!ipBucket.tryConsume(1)) {
            ipRateLimitedCounter.increment();
            log.warn("Rate limit exceeded for IP: clientIp={}, tenantId={}",
                    clientIp, tenantId);
            return RateLimitResult.ipLimited(clientIp);
        }

        return RateLimitResult.permit();
    }

    /**
     * Called by the Resilience4j proxy — either when {@link #tryConsume}
     * throws a matching exception, or when the circuit is already OPEN
     * (in which case {@code ex} is a
     * {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException},
     * not a Redis exception — the broad {@code Exception} parameter type
     * here is Resilience4j's own required convention for fallback
     * methods). Same fail-open behavior the removed internal try/catch
     * used to provide directly — a rate limiter that itself takes the
     * ingestion pipeline down during a Redis blip would be a worse
     * outcome than temporarily under-enforcing limits.
     */
    RateLimitResult tryConsumeFallback(String tenantId, String clientIp, Exception ex) {
        redisErrorCounter.increment();
        log.error("Redis unavailable during rate limit check — failing open: " +
                        "tenantId={}, clientIp={}, error={}",
                tenantId, clientIp, ex.getMessage(), ex);
        return RateLimitResult.permit();
    }
}