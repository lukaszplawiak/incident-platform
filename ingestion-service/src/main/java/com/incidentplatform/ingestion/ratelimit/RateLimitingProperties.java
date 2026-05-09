package com.incidentplatform.ingestion.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed configuration for the rate limiting subsystem.
 *
 * <p>Replaces scattered {@code @Value} annotations in {@link RateLimitingConfig}
 * and {@link RateLimitingService} with a single validated, type-safe record.
 * All properties are bound from the {@code rate-limiting} prefix in
 * {@code application.yml}.
 *
 * <p>Example configuration:
 * <pre>{@code
 * rate-limiting:
 *   enabled: true
 *   tenant:
 *     capacity: 100
 *     refill-tokens: 10
 *     refill-period-seconds: 1
 *   ip:
 *     capacity: 50
 *     refill-tokens: 5
 *     refill-period-seconds: 1
 *   severity:
 *     critical:
 *       capacity: 1000
 *     high:
 *       capacity: 500
 *     medium:
 *       capacity: 100
 *     low:
 *       capacity: 50
 * }</pre>
 */
@ConfigurationProperties(prefix = "rate-limiting")
public record RateLimitingProperties(

        // Whether rate limiting is active. Can be disabled in local/test
        // environments via RATE_LIMITING_ENABLED=false without code changes.
        boolean enabled,

        Tenant tenant,
        Ip ip,
        Severity severity

) {

    public record Tenant(
            long capacity,
            long refillTokens,
            long refillPeriodSeconds
    ) {}

    public record Ip(
            long capacity,
            long refillTokens,
            long refillPeriodSeconds
    ) {}

    public record Severity(
            SeverityLimit critical,
            SeverityLimit high,
            SeverityLimit medium,
            SeverityLimit low
    ) {}

    public record SeverityLimit(long capacity) {}
}