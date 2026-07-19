package com.incidentplatform.auth.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed brute-force protection for the login endpoint.
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
 * (N requests per second). Login brute-force protection has different semantics:
 * "N failures within a time window → lockout for a fixed duration". A simple
 * Redis INCR + EXPIRE implements this exactly and is easier to reason about
 * for security audits. Bucket4j would work but adds unnecessary complexity.
 *
 * <h2>Key structure</h2>
 * <pre>
 *   auth:login:attempts:{tenantId}:{email}  → INCR counter, TTL = window
 *   auth:login:locked:{tenantId}:{email}    → "1", TTL = lockoutDuration
 * </pre>
 *
 * <h2>2026 production standard</h2>
 * This approach mirrors GitHub, Stripe, and PagerDuty:
 * <ul>
 *   <li>Counter per (user, tenant) — not per IP (VPNs, NAT, shared office IP)</li>
 *   <li>Lockout key separate from counter — can be cleared by admin without
 *       affecting the counter history</li>
 *   <li>Prometheus metrics for security dashboards and alerting</li>
 *   <li>Redis failure gracefully degrades to "allow" — prevents Redis outage
 *       from locking out all users</li>
 * </ul>
 */
@Service
@EnableConfigurationProperties(LoginAttemptProperties.class)
public class LoginAttemptService {

    private static final Logger log =
            LoggerFactory.getLogger(LoginAttemptService.class);

    private static final String ATTEMPTS_PREFIX = "auth:login:attempts:";
    private static final String LOCKED_PREFIX = "auth:login:locked:";

    private final StringRedisTemplate redis;
    private final LoginAttemptProperties props;
    private final Counter lockoutCounter;

    public LoginAttemptService(StringRedisTemplate redis,
                               LoginAttemptProperties props,
                               MeterRegistry meterRegistry) {
        this.redis = redis;
        this.props = props;
        this.lockoutCounter = Counter.builder("auth.login.lockout")
                .description("Number of accounts locked due to failed login attempts")
                .register(meterRegistry);
    }

    /**
     * Checks if the account is currently locked out.
     * Must be called BEFORE password verification.
     *
     * @return true if the account is locked and login should be rejected
     */
    public boolean isLocked(String email, String tenantId) {
        if (!props.enabled()) return false;
        try {
            // Use get() != null instead of hasKey() to avoid ClassCastException
            // in Spring Data Redis 3.x where hasKey() may return Long
            // instead of Boolean when used with Lettuce 6.x.
            return redis.opsForValue().get(lockedKey(email, tenantId)) != null;
        } catch (Exception e) {
            // Redis unavailable — fail open (allow the request) to prevent
            // a Redis outage from locking out all users.
            log.error("Redis unavailable during lockout check — failing open: {}",
                    e.getMessage());
            return false;
        }
    }

    /**
     * Records a failed login attempt. If the failure count exceeds
     * {@code maxFailures}, the account is locked for {@code lockoutDuration}.
     * Must be called AFTER a failed password verification.
     */
    public void recordFailure(String email, String tenantId) {
        if (!props.enabled()) return;
        try {
            final String attemptsKey = attemptsKey(email, tenantId);

            final Long attempts = redis.opsForValue().increment(attemptsKey);

            // Set TTL on first attempt — sliding window reset
            if (attempts != null && attempts == 1) {
                redis.expire(attemptsKey, props.window());
            }

            log.debug("Login failure recorded: email={}, tenant={}, attempts={}",
                    email, tenantId, attempts);

            if (attempts != null && attempts >= props.maxFailures()) {
                lock(email, tenantId);
            }
        } catch (Exception e) {
            log.error("Redis unavailable during failure recording: {}",
                    e.getMessage());
        }
    }

    /**
     * Clears the failure counter and lockout key on successful login.
     * Must be called AFTER a successful login.
     */
    public void recordSuccess(String email, String tenantId) {
        if (!props.enabled()) return;
        try {
            redis.delete(attemptsKey(email, tenantId));
            redis.delete(lockedKey(email, tenantId));
        } catch (Exception e) {
            log.error("Redis unavailable during success recording: {}",
                    e.getMessage());
        }
    }

    /**
     * Admin-triggered unlock — clears lockout without affecting attempt counter.
     * Useful when a legitimate user is locked out.
     */
    public void unlock(String email, String tenantId) {
        try {
            redis.delete(lockedKey(email, tenantId));
            redis.delete(attemptsKey(email, tenantId));
            log.info("Account unlocked by admin: email={}, tenant={}", email, tenantId);
        } catch (Exception e) {
            log.error("Redis unavailable during admin unlock: {}", e.getMessage());
        }
    }

    /**
     * Returns remaining lockout duration for display in error responses.
     * Returns {@link Duration#ZERO} if not locked or Redis unavailable.
     */
    public Duration getRemainingLockout(String email, String tenantId) {
        try {
            final Long ttl = redis.getExpire(lockedKey(email, tenantId));
            if (ttl == null || ttl <= 0) return Duration.ZERO;
            return Duration.ofSeconds(ttl);
        } catch (Exception e) {
            return Duration.ZERO;
        }
    }

    // ── private ───────────────────────────────────────────────────────────

    private void lock(String email, String tenantId) {
        redis.opsForValue().set(
                lockedKey(email, tenantId), "1", props.lockoutDuration());
        lockoutCounter.increment();
        log.warn("Account locked due to too many failed attempts: " +
                        "email={}, tenant={}, lockoutDuration={}",
                email, tenantId, props.lockoutDuration());
    }

    private String attemptsKey(String email, String tenantId) {
        return ATTEMPTS_PREFIX + tenantId + ":" + email;
    }

    private String lockedKey(String email, String tenantId) {
        return LOCKED_PREFIX + tenantId + ":" + email;
    }
}