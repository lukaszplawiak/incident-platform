package com.incidentplatform.auth.ratelimit;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration for {@link BruteForceProtectionService}.
 *
 * <h2>Renamed from LoginAttemptProperties (backlog #58)</h2>
 * The underlying mechanism (sliding-window failure counter + lockout,
 * Redis-backed) is not specific to the login endpoint — it now also
 * protects MFA verification (see {@link BruteForceProtectionService.Scope}).
 * {@code LoginAttemptProperties} would have been a misleading name once
 * a second, independently-counted scope existed. One shared set of
 * thresholds is used for every scope today — see
 * {@code BruteForceProtectionService}'s own Javadoc for why scope-specific
 * thresholds were deliberately not added in this pass.
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
 * brute-force-protection:
 *   enabled: ${BRUTE_FORCE_PROTECTION_ENABLED:true}
 *   max-failures: ${BRUTE_FORCE_PROTECTION_MAX_FAILURES:5}
 *   lockout-duration: ${BRUTE_FORCE_PROTECTION_LOCKOUT_DURATION:PT15M}
 *   window: ${BRUTE_FORCE_PROTECTION_WINDOW:PT10M}
 * }</pre>
 */
@ConfigurationProperties(prefix = "brute-force-protection")
@Validated
public record BruteForceProtectionProperties(

        boolean enabled,

        @Positive(message = "brute-force-protection.max-failures must be positive")
        int maxFailures,

        @NotNull(message = "brute-force-protection.lockout-duration must not be null")
        Duration lockoutDuration,

        @NotNull(message = "brute-force-protection.window must not be null")
        Duration window

) {}