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
 * {@link com.incidentplatform.auth.ratelimit.LoginAttemptProperties}.
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
@Validated
public record RateLimitingProperties(

        boolean enabled,

        @NotNull @Valid Tenant tenant,
        @NotNull @Valid Ip ip,
        @NotNull @Valid Severity severity

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

    public record Severity(
            @NotNull @Valid SeverityLimit critical,
            @NotNull @Valid SeverityLimit high,
            @NotNull @Valid SeverityLimit medium,
            @NotNull @Valid SeverityLimit low
    ) {}

    public record SeverityLimit(
            @Positive(message = "rate-limiting severity capacity must be positive")
            long capacity
    ) {}
}