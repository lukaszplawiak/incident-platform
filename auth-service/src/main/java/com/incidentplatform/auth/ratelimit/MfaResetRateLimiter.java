package com.incidentplatform.auth.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Limits on admins resetting other users' MFA (backlog #0-88, found in
 * review): per admin and per tenant, so several taken-over admin accounts of
 * one tenant do not multiply the per-admin number.
 *
 * <h2>Why</h2>
 * A reset removes a user's factor and ends every session. An admin session
 * that passes the MFA rule can still be taken over (a stolen device, a rogue
 * admin); without a limit it could strip the MFA of a whole tenant in a loop.
 * Each reset is emailed and audited, but those only tell afterwards; the limit
 * bounds the damage, and reaching it alerts the operator
 * ({@code AdminMfaResetRateLimited}).
 *
 * <h2>Fail-closed</h2>
 * As the platform API's limiter ({@link PlatformRateLimiter}, backlog #0-83)
 * and for the same reason: a rare, privileged security operation, where
 * waiting a minute costs little and a limit that vanishes with Redis is the
 * wrong default. While Redis cannot be checked a reset answers 503 with
 * Retry-After. Ingestion's limiter stays fail-open (#67): alerts must not be
 * lost.
 *
 * <p>Same Redis connection as the platform limiter (lazy, so auth-service
 * starts without Redis), its own circuit breaker {@code mfa-reset-ratelimit}
 * and metrics. Counts only resets about to happen (the admin's step-up passed,
 * the user exists and has a factor), so refused attempts cannot use up a
 * tenant's budget. A token taken is not given back: if the reset then fails
 * (a database error, the email request), or the tenant's bucket refuses after
 * the admin's gave a token, that token stays spent. Both err towards limiting
 * more, the safe direction for this limit, and keep it a single pass with no
 * compensation step (found in review, documented rather than changed).
 */
@Service
public class MfaResetRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(MfaResetRateLimiter.class);

    static final String ADMIN_KEY_PREFIX = "ratelimit:mfa-reset:admin:";
    static final String TENANT_KEY_PREFIX = "ratelimit:mfa-reset:tenant:";

    /** Tag of {@code auth.mfa_reset.ratelimit.rejected}: which limit refused. */
    static final String LIMIT_TAG = "limit";

    /** Retry-After while Redis cannot be checked: the breaker's open-state wait. */
    static final long UNAVAILABLE_RETRY_AFTER_SECONDS = 30;

    private final ProxyManager<String> proxyManager;
    private final BucketConfiguration adminBucket;
    private final BucketConfiguration tenantBucket;
    private final Counter rejectedByAdminLimit;
    private final Counter rejectedByTenantLimit;
    private final Counter unavailable;

    public MfaResetRateLimiter(
            @Lazy @Qualifier("platformRateLimitProxyManager") ProxyManager<String> proxyManager,
            @Value("${mfa-reset.rate-limit.per-admin-per-hour:10}") long perAdminPerHour,
            @Value("${mfa-reset.rate-limit.per-tenant-per-hour:30}") long perTenantPerHour,
            MeterRegistry meterRegistry) {
        if (perAdminPerHour < 1) {
            throw new IllegalArgumentException(
                    "mfa-reset.rate-limit.per-admin-per-hour must be at least 1, was " + perAdminPerHour);
        }
        if (perTenantPerHour < perAdminPerHour) {
            throw new IllegalArgumentException("mfa-reset.rate-limit.per-tenant-per-hour (" + perTenantPerHour
                    + ") must be at least mfa-reset.rate-limit.per-admin-per-hour (" + perAdminPerHour + ")");
        }
        this.proxyManager = proxyManager;
        this.adminBucket = RedisTokenBuckets.perHour(perAdminPerHour);
        this.tenantBucket = RedisTokenBuckets.perHour(perTenantPerHour);
        this.rejectedByAdminLimit = rejectedCounter("admin", meterRegistry);
        this.rejectedByTenantLimit = rejectedCounter("tenant", meterRegistry);
        this.unavailable = Counter.builder("auth.mfa_reset.ratelimit.unavailable")
                .description("Admin MFA resets refused because the limit could not be checked in Redis "
                        + "(fail-closed, backlog #0-88)")
                .register(meterRegistry);
    }

    private static Counter rejectedCounter(String limit, MeterRegistry meterRegistry) {
        // No tenant tag: the alert stays content-free for the operator; the
        // WARN log names the admin and the tenant.
        return Counter.builder("auth.mfa_reset.ratelimit.rejected")
                .description("Admin MFA resets refused by a rate limit (backlog #0-88)")
                .tag(LIMIT_TAG, limit)
                .register(meterRegistry);
    }

    /**
     * Takes one reset from the admin's bucket, then from the tenant's. No
     * try/catch: a Redis failure must reach the {@code @CircuitBreaker} proxy
     * to be recorded, and its fallback turns it into
     * {@link RateLimitDecision.Outcome#UNAVAILABLE}.
     */
    @CircuitBreaker(name = "mfa-reset-ratelimit", fallbackMethod = "unavailable")
    public RateLimitDecision tryConsume(UUID adminUserId, String tenantId) {
        final ConsumptionProbe admin =
                RedisTokenBuckets.consume(proxyManager, ADMIN_KEY_PREFIX + adminUserId, adminBucket);
        if (!admin.isConsumed()) {
            return limited(admin, rejectedByAdminLimit, "per-admin", adminUserId, tenantId);
        }
        final ConsumptionProbe tenant =
                RedisTokenBuckets.consume(proxyManager, TENANT_KEY_PREFIX + tenantId, tenantBucket);
        if (!tenant.isConsumed()) {
            return limited(tenant, rejectedByTenantLimit, "per-tenant", adminUserId, tenantId);
        }
        return RateLimitDecision.ALLOWED;
    }

    private static RateLimitDecision limited(ConsumptionProbe probe, Counter rejected, String limit,
                                             UUID adminUserId, String tenantId) {
        rejected.increment();
        final long retryAfter = RedisTokenBuckets.retryAfterSeconds(probe);
        log.warn("Admin MFA reset refused by the {} limit: admin={}, tenant={}, retryAfterSeconds={}",
                limit, adminUserId, tenantId, retryAfter);
        return new RateLimitDecision(RateLimitDecision.Outcome.LIMITED, retryAfter);
    }

    RateLimitDecision unavailable(UUID adminUserId, String tenantId, Throwable cause) {
        unavailable.increment();
        if (cause instanceof CallNotPermittedException) {
            log.warn("Admin MFA reset limit unavailable (circuit open) — refusing the reset (fail-closed): "
                    + "admin={}, tenant={}", adminUserId, tenantId);
        } else {
            log.error("Admin MFA reset limit cannot be checked in Redis — refusing the reset (fail-closed): "
                    + "admin={}, tenant={}", adminUserId, tenantId, cause);
        }
        return new RateLimitDecision(RateLimitDecision.Outcome.UNAVAILABLE, UNAVAILABLE_RETRY_AFTER_SECONDS);
    }
}
