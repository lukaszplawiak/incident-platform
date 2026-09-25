package com.incidentplatform.ingestion.ratelimit;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Limit on <em>failed</em> API key authentications per client IP (backlog #0-16),
 * checked before a key is sent to auth-service for introspection.
 *
 * <p>Defaults: 10 failures, refilled 10 per 60 s — generous for an operator
 * who pasted the wrong key and fixes it, tight enough that a stream of random
 * keys cannot turn into a stream of auth-service calls.
 */
@ConfigurationProperties(prefix = "rate-limiting.auth-failure")
@Validated
public record AuthFailureRateLimitProperties(
        @Positive(message = "rate-limiting.auth-failure.capacity must be positive")
        long capacity,

        @Positive(message = "rate-limiting.auth-failure.refill-tokens must be positive")
        long refillTokens,

        @Positive(message = "rate-limiting.auth-failure.refill-period-seconds must be positive")
        long refillPeriodSeconds
) {
}
