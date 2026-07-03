package com.incidentplatform.auth.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the login brute-force protection mechanism.
 *
 * <h2>Defaults</h2>
 * <ul>
 *   <li>Max 5 failed attempts before lockout</li>
 *   <li>Lockout duration: 15 minutes</li>
 *   <li>Attempt window: 10 minutes (attempts outside this window don't count)</li>
 * </ul>
 *
 * <p>These are conservative defaults aligned with OWASP recommendations.
 * Tune per environment via {@code login-attempt.*} properties.
 *
 * <p>Example:
 * <pre>{@code
 * login-attempt:
 *   enabled: true
 *   max-failures: 5
 *   lockout-duration: PT15M
 *   window: PT10M
 * }</pre>
 */
@ConfigurationProperties(prefix = "login-attempt")
public record LoginAttemptProperties(
        boolean enabled,
        int maxFailures,
        Duration lockoutDuration,
        Duration window
) {
    public LoginAttemptProperties {
        if (maxFailures <= 0) throw new IllegalArgumentException(
                "maxFailures must be positive");
        if (lockoutDuration == null || lockoutDuration.isNegative())
            throw new IllegalArgumentException("lockoutDuration must be positive");
        if (window == null || window.isNegative())
            throw new IllegalArgumentException("window must be positive");
    }
}