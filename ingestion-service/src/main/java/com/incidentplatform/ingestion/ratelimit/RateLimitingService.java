package com.incidentplatform.ingestion.ratelimit;

import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimitingService {

    private static final Logger log =
            LoggerFactory.getLogger(RateLimitingService.class);

    private final RateLimitingConfig config;

    @Value("${rate-limiting.enabled:true}")
    private boolean enabled;

    private final ConcurrentHashMap<String, Bucket> tenantBuckets =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> ipBuckets =
            new ConcurrentHashMap<>();

    private final Counter tenantRateLimitedCounter;
    private final Counter ipRateLimitedCounter;

    public RateLimitingService(RateLimitingConfig config,
                               MeterRegistry meterRegistry) {
        this.config = config;

        this.tenantRateLimitedCounter = Counter.builder("rate_limit.tenant.rejected")
                .description("Number of requests rejected by tenant rate limiter")
                .register(meterRegistry);

        this.ipRateLimitedCounter = Counter.builder("rate_limit.ip.rejected")
                .description("Number of requests rejected by IP rate limiter")
                .register(meterRegistry);
    }

    public RateLimitResult tryConsume(String tenantId, String clientIp) {
        if (!enabled) {
            return RateLimitResult.permit();
        }

        final Bucket tenantBucket = tenantBuckets.computeIfAbsent(
                tenantId, k -> config.createTenantBucket());

        if (!tenantBucket.tryConsume(1)) {
            tenantRateLimitedCounter.increment();
            log.warn("Rate limit exceeded for tenant: tenantId={}, " +
                    "clientIp={}", tenantId, clientIp);
            return RateLimitResult.tenantLimited(tenantId);
        }

        final Bucket ipBucket = ipBuckets.computeIfAbsent(
                clientIp, k -> config.createIpBucket());

        if (!ipBucket.tryConsume(1)) {
            ipRateLimitedCounter.increment();
            log.warn("Rate limit exceeded for IP: clientIp={}, " +
                    "tenantId={}", clientIp, tenantId);
            return RateLimitResult.ipLimited(clientIp);
        }

        return RateLimitResult.permit();
    }
}