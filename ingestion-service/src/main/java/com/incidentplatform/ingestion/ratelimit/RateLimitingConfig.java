package com.incidentplatform.ingestion.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RateLimitingConfig {

    // Per tenant limits
    @Value("${rate-limiting.tenant.capacity:100}")
    private long tenantCapacity;

    @Value("${rate-limiting.tenant.refill-tokens:10}")
    private long tenantRefillTokens;

    @Value("${rate-limiting.tenant.refill-period-seconds:1}")
    private long tenantRefillPeriodSeconds;

    // Per IP limits
    @Value("${rate-limiting.ip.capacity:50}")
    private long ipCapacity;

    @Value("${rate-limiting.ip.refill-tokens:5}")
    private long ipRefillTokens;

    @Value("${rate-limiting.ip.refill-period-seconds:1}")
    private long ipRefillPeriodSeconds;

    // Per severity limits (capacity)
    @Value("${rate-limiting.severity.critical.capacity:1000}")
    private long criticalCapacity;

    @Value("${rate-limiting.severity.high.capacity:500}")
    private long highCapacity;

    @Value("${rate-limiting.severity.medium.capacity:100}")
    private long mediumCapacity;

    @Value("${rate-limiting.severity.low.capacity:50}")
    private long lowCapacity;

    public Bucket createTenantBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(tenantCapacity)
                        .refillGreedy(tenantRefillTokens,
                                Duration.ofSeconds(tenantRefillPeriodSeconds))
                        .build())
                .build();
    }

    public Bucket createIpBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(ipCapacity)
                        .refillGreedy(ipRefillTokens,
                                Duration.ofSeconds(ipRefillPeriodSeconds))
                        .build())
                .build();
    }

    public long getSeverityCapacity(String severity) {
        if (severity == null) return mediumCapacity;
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> criticalCapacity;
            case "HIGH"     -> highCapacity;
            case "LOW"      -> lowCapacity;
            default         -> mediumCapacity;
        };
    }

    public long getTenantCapacity()            { return tenantCapacity; }
    public long getTenantRefillTokens()        { return tenantRefillTokens; }
    public long getTenantRefillPeriodSeconds() { return tenantRefillPeriodSeconds; }
    public long getIpCapacity()                { return ipCapacity; }
    public long getIpRefillTokens()            { return ipRefillTokens; }
    public long getIpRefillPeriodSeconds()     { return ipRefillPeriodSeconds; }
}