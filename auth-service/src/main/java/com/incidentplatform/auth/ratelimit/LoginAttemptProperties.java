package com.incidentplatform.auth.ratelimit;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration for the login brute-force protection mechanism.
 *
 * <h2>Upgrade from compact constructor validation to @Validated + Bean Validation</h2>
 * Previously this record used a compact constructor with manual {@code if} checks
 * throwing {@link IllegalArgumentException}. This works but has two drawbacks:
 * <ol>
 *   <li>Only the first violation is reported — if both {@code maxFailures} and
 *       {@code lockoutDuration} are invalid, the developer sees only the first error
 *       and must fix-and-retry to discover the second.</li>
 *   <li>The error message comes from an unchecked exception during context
 *       initialization rather than the structured {@code BindValidationException}
 *       that Spring Boot produces for {@code @Validated} failures — making it
 *       harder to identify which property caused the problem.</li>
 * </ol>
 *
 * <p>With {@code @Validated}, Spring Boot validates all constraints at once
 * during context startup and produces a structured report listing every
 * violation. This is consistent with the approach used for
 * {@link com.incidentplatform.shared.security.JwtProperties}.
 *
 * <h2>Defaults</h2>
 * <ul>
 *   <li>Max 5 failed attempts before lockout — OWASP recommendation</li>
 *   <li>Lockout duration: 15 minutes</li>
 *   <li>Attempt window: 10 minutes (attempts outside this window don't count)</li>
 * </ul>
 *
 * <h2>YAML configuration</h2>
 * <pre>{@code
 * login-attempt:
 *   enabled: true
 *   max-failures: ${LOGIN_ATTEMPT_MAX_FAILURES:5}
 *   lockout-duration: ${LOGIN_ATTEMPT_LOCKOUT_DURATION:PT15M}
 *   window: ${LOGIN_ATTEMPT_WINDOW:PT10M}
 * }</pre>
 */
@ConfigurationProperties(prefix = "login-attempt")
@Validated
public record LoginAttemptProperties(

        boolean enabled,

        @Positive(message = "login-attempt.max-failures must be positive")
        int maxFailures,

        @NotNull(message = "login-attempt.lockout-duration must not be null")
        Duration lockoutDuration,

        @NotNull(message = "login-attempt.window must not be null")
        Duration window

) {}