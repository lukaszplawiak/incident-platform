package com.incidentplatform.ingestion.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.distributed.proxy.ProxyManager;
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
 * (same module) and {@code LoginAttemptService} (auth-service) already
 * solve the identical class of problem: move the per-key state to
 * Redis, which expires it automatically and shares it across every
 * replica. See {@link RedisRateLimitConfig} for the Redis wiring and
 * {@link RateLimitingConfig} for why it now hands out reusable
 * {@code BucketConfiguration} rules instead of per-key {@code Bucket}
 * instances.
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

    public RateLimitResult tryConsume(String tenantId, String clientIp) {
        if (!enabled) {
            return RateLimitResult.permit();
        }

        // Redis errors fail OPEN (request allowed through), matching
        // DeduplicationService and LoginAttemptService in this same
        // codebase: a rate limiter that itself takes the ingestion
        // pipeline down during a Redis blip would be a worse outcome
        // than temporarily under-enforcing limits.
        try {
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

        } catch (Exception e) {
            redisErrorCounter.increment();
            log.error("Redis unavailable during rate limit check — failing open: " +
                            "tenantId={}, clientIp={}, error={}",
                    tenantId, clientIp, e.getMessage(), e);
            return RateLimitResult.permit();
        }
    }
}