package com.incidentplatform.ingestion.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly-typed, validated configuration for the rate limiting subsystem.
 *
 * <h2>Upgrade from no validation to @Validated + Bean Validation</h2>
 * Previously this record had no validation — a zero or negative capacity
 * would silently create a broken rate limiter that rejects all requests.
 * Now {@code @Validated} triggers Bean Validation at startup, reporting
 * all violations at once via {@code BindValidationException} before any
 * request is processed.
 *
 * <p>Consistent with the approach used for
 * {@link com.incidentplatform.shared.security.JwtProperties} and
 * {@code com.incidentplatform.auth.ratelimit.BruteForceProtectionProperties}
 * (renamed from {@code LoginAttemptProperties} — backlog #58).
 *
 * <h2>Fixed (backlog #73): removed the unused, half-built {@code severity}
 * section</h2>
 * A third {@code severity} component (fixed capacity per CRITICAL/HIGH/
 * MEDIUM/LOW) previously sat alongside {@code tenant}/{@code ip} here,
 * with a reader ({@code RateLimitingConfig.getSeverityCapacity}) that
 * had no caller anywhere. See that method's own former Javadoc (now in
 * {@code RateLimitingConfig}'s class-level Javadoc) for the full account
 * of why completing it as originally shaped would have been the wrong
 * mechanism, not just an unfinished one — genuine severity-aware
 * protection belongs in {@code application.yml}'s already-planned
 * "Layer 4: Separate Kafka topics per severity" instead.
 *
 * <h2>YAML configuration</h2>
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
 * }</pre>
 */
@ConfigurationProperties(prefix = "rate-limiting")
@Validated
public record RateLimitingProperties(

        boolean enabled,

        @NotNull @Valid Tenant tenant,
        @NotNull @Valid Ip ip

) {

    public record Tenant(
            @Positive(message = "rate-limiting.tenant.capacity must be positive")
            long capacity,

            @Positive(message = "rate-limiting.tenant.refill-tokens must be positive")
            long refillTokens,

            @Positive(message = "rate-limiting.tenant.refill-period-seconds must be positive")
            long refillPeriodSeconds
    ) {}

    public record Ip(
            @Positive(message = "rate-limiting.ip.capacity must be positive")
            long capacity,

            @Positive(message = "rate-limiting.ip.refill-tokens must be positive")
            long refillTokens,

            @Positive(message = "rate-limiting.ip.refill-period-seconds must be positive")
            long refillPeriodSeconds
    ) {}
}