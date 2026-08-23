package com.incidentplatform.auth.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed brute-force protection — a sliding-window failure counter
 * per {@link Scope}, with lockout once {@code maxFailures} is exceeded.
 *
 * <h2>Renamed and generalized from LoginAttemptService (backlog #58)</h2>
 * Originally protected only the login endpoint (password verification).
 * Found during an MFA-focused review that {@code MfaService.verifyMfaToken}/
 * {@code verifyWithBackupCode} had no equivalent protection at all — an
 * attacker who already has a valid password (leaked, reused, phished)
 * could cycle through unlimited login attempts, getting one fresh TOTP/
 * backup-code guess per cycle via a freshly issued, single-use
 * {@code AuthToken.Type.MFA_SESSION} token (see
 * {@code AuthTokenService.consumeToken}, backlog #53) with no overall
 * limit on how many total guesses accumulate over time. The login
 * endpoint's own lockout only counts password failures — a correct
 * password never increments it, however many times it's retried.
 *
 * <p>Rather than duplicate this whole mechanism into a second,
 * near-identical class (real risk of the two drifting out of sync over
 * time — the same reasoning already documented on
 * {@code AuditEventPublisher.publishAuth}/{@code publishIncident} for
 * why a single, scope-parameterized method is preferred over one method
 * per event type), this class now takes a {@link Scope} parameter on
 * every method, keying the Redis counter independently per scope.
 * Login and MFA failures are counted completely separately — a wrong
 * password never contributes to an MFA lockout and vice versa — while
 * sharing one proven, audited implementation.
 *
 * <h2>One shared threshold configuration, not per-scope</h2>
 * {@link BruteForceProtectionProperties} still defines a single
 * {@code maxFailures}/{@code window}/{@code lockoutDuration}, applied to
 * every scope. Deliberately not made scope-specific in this pass — real,
 * independently-counted lockouts per scope is what closes the actual
 * vulnerability; independently-*configurable thresholds* per scope is a
 * separate, smaller refinement with no identified need yet. Can be
 * revisited if a genuine reason to tune login vs. MFA thresholds
 * differently ever comes up.
 *
 * <h2>Why Redis instead of in-memory?</h2>
 * In-memory counters (e.g. {@code ConcurrentHashMap}) do not survive service
 * restarts and are not shared across multiple auth-service instances running
 * in Kubernetes. An attacker can reset the counter simply by waiting for a
 * pod restart or by routing requests to different pods. Redis provides a
 * durable, distributed counter that works correctly in all deployment topologies.
 *
 * <h2>Why a sliding window counter instead of Bucket4j?</h2>
 * Bucket4j token-bucket algorithm is designed for throughput rate limiting
 * (N requests per second). Brute-force protection has different semantics:
 * "N failures within a time window → lockout for a fixed duration". A simple
 * Redis INCR + EXPIRE implements this exactly and is easier to reason about
 * for security audits. Bucket4j would work but adds unnecessary complexity.
 *
 * <h2>Key structure</h2>
 * <pre>
 *   auth:{scope}:attempts:{tenantId}:{identifier}  → INCR counter, TTL = window
 *   auth:{scope}:locked:{tenantId}:{identifier}    → "1", TTL = lockoutDuration
 * </pre>
 * {@code identifier} is whatever uniquely names the subject being
 * protected within that scope — the email typed into the login form for
 * {@link Scope#LOGIN}, or the authenticated user's ID for
 * {@link Scope#MFA} (MFA verification has no email in its request body —
 * only an opaque session token; see {@code MfaService} for how the user
 * is resolved before this check runs).
 *
 * <h2>2026 production standard</h2>
 * This approach mirrors GitHub, Stripe, and PagerDuty:
 * <ul>
 *   <li>Counter per (identifier, tenant, scope) — not per IP (VPNs, NAT,
 *       shared office IP)</li>
 *   <li>Lockout key separate from counter — can be cleared by admin without
 *       affecting the counter history</li>
 *   <li>Prometheus metrics for security dashboards and alerting</li>
 *   <li>Redis failure gracefully degrades to "allow" — prevents Redis outage
 *       from locking out all users</li>
 * </ul>
 */
@Service
@EnableConfigurationProperties(BruteForceProtectionProperties.class)
public class BruteForceProtectionService {

    /**
     * What kind of failure is being counted. Deliberately a closed enum,
     * not a raw {@code String} — a typo'd scope string would silently
     * create an entirely separate, never-checked counter, defeating the
     * protection without any error.
     */
    public enum Scope {
        LOGIN("login"),
        MFA("mfa");

        private final String key;

        Scope(String key) {
            this.key = key;
        }
    }

    private static final Logger log =
            LoggerFactory.getLogger(BruteForceProtectionService.class);

    private static final String KEY_PREFIX = "auth:";
    private static final String ATTEMPTS_SEGMENT = ":attempts:";
    private static final String LOCKED_SEGMENT = ":locked:";

    private final StringRedisTemplate redis;
    private final BruteForceProtectionProperties props;
    private final MeterRegistry meterRegistry;

    public BruteForceProtectionService(StringRedisTemplate redis,
                                       BruteForceProtectionProperties props,
                                       MeterRegistry meterRegistry) {
        this.redis = redis;
        this.props = props;
        this.meterRegistry = meterRegistry;
    }

/**
 * Checks if the subject is currently locked out for this scope.
 * Must be called BEFORE verifying whatever credential this scope
 * protects (password, TOTP code, backup code) — checking first
 * prevents timing attacks: verifying the credential first would let
 * an attacker use timing differences to enumerate valid
 * identifiers/tenants even while locked out.
 *
 * @return true if the subject is locked and the request should be rejected
 */
public boolean isLocked(Scope scope, String identifier, String tenantId) {
    if (!props.enabled()) return false;
    try {
        // Use get() != null instead of hasKey() to avoid ClassCastException
        // in Spring Data Redis 3.x where hasKey() may return Long
        // instead of Boolean when used with Lettuce 6.x.
        return redis.opsForValue().get(lockedKey(scope, identifier, tenantId)) != null;
    } catch (Exception e) {
        // Redis unavailable — fail open (allow the request) to prevent
        // a Redis outage from locking out all users.
        log.error("Redis unavailable during lockout check — failing open: " +
                "scope={}, error={}", scope.key, e.getMessage());
        return false;
    }
}

    /**
     * Records a failed attempt for this scope. If the failure count exceeds
     * {@code maxFailures}, the subject is locked for {@code lockoutDuration}.
     * Must be called AFTER a failed credential verification.
     */
    public void recordFailure(Scope scope, String identifier, String tenantId) {
        if (!props.enabled()) return;
        try {
            final String attemptsKey = attemptsKey(scope, identifier, tenantId);

            final Long attempts = redis.opsForValue().increment(attemptsKey);

            // Set TTL on first attempt — sliding window reset
            if (attempts != null && attempts == 1) {
                redis.expire(attemptsKey, props.window());
            }

            log.debug("Failure recorded: scope={}, identifier={}, tenant={}, attempts={}",
                    scope.key, identifier, tenantId, attempts);

            if (attempts != null && attempts >= props.maxFailures()) {
                lock(scope, identifier, tenantId);
            }
        } catch (Exception e) {
            log.error("Redis unavailable during failure recording: scope={}, error={}",
                    scope.key, e.getMessage());
        }
    }

    /**
     * Clears the failure counter and lockout key for this scope on
     * successful verification. Must be called AFTER a successful
     * credential verification.
     */
    public void recordSuccess(Scope scope, String identifier, String tenantId) {
        if (!props.enabled()) return;
        try {
            redis.delete(attemptsKey(scope, identifier, tenantId));
            redis.delete(lockedKey(scope, identifier, tenantId));
        } catch (Exception e) {
            log.error("Redis unavailable during success recording: scope={}, error={}",
                    scope.key, e.getMessage());
        }
    }

    /**
     * Admin-triggered unlock for this scope — clears lockout without
     * affecting attempt counter.  Useful when a legitimate user is locked out.
     */
    public void unlock(Scope scope, String identifier, String tenantId) {
        try {
            redis.delete(lockedKey(scope, identifier, tenantId));
            redis.delete(attemptsKey(scope, identifier, tenantId));
            log.info("Unlocked by admin: scope={}, identifier={}, tenant={}",
                    scope.key, identifier, tenantId);
        } catch (Exception e) {
            log.error("Redis unavailable during admin unlock: scope={}, error={}",
                    scope.key, e.getMessage());
        }
    }

    /**
     * Returns remaining lockout duration for this scope, for display in
     * error responses. Returns {@link Duration#ZERO} if not locked or
     * Redis unavailable.
     */
    public Duration getRemainingLockout(Scope scope, String identifier, String tenantId) {
        try {
            final Long ttl = redis.getExpire(lockedKey(scope, identifier, tenantId));
            if (ttl == null || ttl <= 0) return Duration.ZERO;
            return Duration.ofSeconds(ttl);
        } catch (Exception e) {
            return Duration.ZERO;
        }
    }

    // ── private ───────────────────────────────────────────────────────────

    private void lock(Scope scope, String identifier, String tenantId) {
        redis.opsForValue().set(
                lockedKey(scope, identifier, tenantId), "1", props.lockoutDuration());
        meterRegistry.counter("auth.lockout", "scope", scope.key).increment();
        log.warn("Locked due to too many failed attempts: " +
                        "scope={}, identifier={}, tenant={}, lockoutDuration={}",
                scope.key, identifier, tenantId, props.lockoutDuration());
    }

    private String attemptsKey(Scope scope, String identifier, String tenantId) {
        return KEY_PREFIX + scope.key + ATTEMPTS_SEGMENT + tenantId + ":" + identifier;
    }

    private String lockedKey(Scope scope, String identifier, String tenantId) {
        return KEY_PREFIX + scope.key + LOCKED_SEGMENT + tenantId + ":" + identifier;
    }
}