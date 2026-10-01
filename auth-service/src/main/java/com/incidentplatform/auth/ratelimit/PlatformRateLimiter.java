package com.incidentplatform.auth.ratelimit;

import io.github.bucket4j.Bandwidth;
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

import java.time.Duration;
import java.util.UUID;

/**
 * Limits on the platform API's write operations (backlog #0-83):
 * creating a tenant and reissuing a first admin invite, both of which send an
 * invite email to an address the caller chooses. Limited per operator and in
 * total. It bounds what a taken-over
 * operator session can do before someone notices; the alerts in
 * docker/prometheus.rules.yml are the noticing.
 *
 * <p>Token buckets in Redis (bucket4j), so the limits hold across replicas and
 * restarts, both refilled gradually over the hour:
 * <ul>
 *   <li>one per operator user id: {@code platform.rate-limit.operations-per-hour}
 *       (default 20);</li>
 *   <li>one for the whole platform API:
 *       {@code platform.rate-limit.global-operations-per-hour} (default 50).
 *       Without it, N taken-over operator accounts could send N times the
 *       per-operator number of invite emails (found in review). It must be at
 *       least the per-operator limit, or that one would never apply.</li>
 * </ul>
 * The operator's bucket is taken first: an operator already over their own
 * limit is refused without touching the shared one, so one operator cannot
 * use up everyone's budget by retrying. When the shared bucket then refuses,
 * the operator's token is spent anyway, as a refused attempt is.
 *
 * <p>Accepted trade-off (found in review): a few taken-over operator accounts
 * together can use up the shared bucket and hold back legitimate onboarding
 * for up to an hour. That is the point of the shared limit (fewer invite
 * emails, not more), it costs only time, and each refusal fires the critical
 * {@code PlatformApiRateLimited} alert, which is the moment to revoke those
 * accounts' sessions.
 *
 * <p>bucket4j stores a bucket's configuration with it in Redis, so a changed
 * limit applies to a bucket only once its key has expired (5 minutes after it
 * would be full again, see {@link PlatformRateLimitConfig}); deleting the
 * {@code ratelimit:platform:*} keys applies it at once.
 *
 * <h2>Fail-closed, unlike ingestion-service (#67)</h2>
 * ingestion-service's limiter fails open: there the limit protects the
 * availability of alert ingestion, and losing alerts during a Redis outage is
 * worse than not limiting them. Here the limit is a security control on the
 * platform's one cross-tenant capability, and the operations are rare and can
 * wait: onboarding a tenant five minutes later costs nothing, while a limit
 * that disappears whenever Redis is unreachable would be off exactly when an
 * attacker could arrange it. So any Redis failure, and an open circuit, answers
 * {@link Outcome#UNAVAILABLE} (the controller: 503 with Retry-After). The
 * circuit breaker ({@code platform-ratelimit}) only makes that answer fast.
 */
@Service
public class PlatformRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(PlatformRateLimiter.class);

    static final String KEY_PREFIX = "ratelimit:platform:operator:";
    static final String GLOBAL_KEY = "ratelimit:platform:global";

    /** Tag of {@code platform.ratelimit.rejected}: which limit refused. */
    static final String LIMIT_TAG = "limit";

    /** Retry-After while Redis cannot be checked: the breaker's open-state wait. */
    static final long UNAVAILABLE_RETRY_AFTER_SECONDS = 30;

    /** What a write operation may do now. */
    public enum Outcome { ALLOWED, LIMITED, UNAVAILABLE }

    /** @param retryAfterSeconds for LIMITED and UNAVAILABLE; 0 when allowed */
    public record Decision(Outcome outcome, long retryAfterSeconds) {
        public boolean allowed() {
            return outcome == Outcome.ALLOWED;
        }
    }

    private final ProxyManager<String> proxyManager;
    private final BucketConfiguration operatorBucket;
    private final BucketConfiguration globalBucket;
    private final Counter rejectedByOperatorLimit;
    private final Counter rejectedByGlobalLimit;
    private final Counter unavailable;

    public PlatformRateLimiter(
            // Lazy: resolved on the first call (see PlatformRateLimitConfig).
            @Lazy @Qualifier("platformRateLimitProxyManager") ProxyManager<String> proxyManager,
            @Value("${platform.rate-limit.operations-per-hour:20}") long operationsPerHour,
            @Value("${platform.rate-limit.global-operations-per-hour:50}") long globalOperationsPerHour,
            MeterRegistry meterRegistry) {
        if (operationsPerHour < 1) {
            throw new IllegalArgumentException(
                    "platform.rate-limit.operations-per-hour must be at least 1, was " + operationsPerHour);
        }
        if (globalOperationsPerHour < operationsPerHour) {
            throw new IllegalArgumentException("platform.rate-limit.global-operations-per-hour ("
                    + globalOperationsPerHour + ") must be at least platform.rate-limit.operations-per-hour ("
                    + operationsPerHour + ")");
        }
        this.proxyManager = proxyManager;
        this.operatorBucket = perHour(operationsPerHour);
        this.globalBucket = perHour(globalOperationsPerHour);
        this.rejectedByOperatorLimit = rejectedCounter("operator", meterRegistry);
        this.rejectedByGlobalLimit = rejectedCounter("global", meterRegistry);
        this.unavailable = Counter.builder("platform.ratelimit.unavailable")
                .description("Platform API write operations refused because the limit could not be "
                        + "checked in Redis (fail-closed, backlog #0-83)")
                .register(meterRegistry);
    }

    private static BucketConfiguration perHour(long operations) {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(operations)
                        .refillGreedy(operations, Duration.ofHours(1))
                        .build())
                .build();
    }

    private static Counter rejectedCounter(String limit, MeterRegistry meterRegistry) {
        return Counter.builder("platform.ratelimit.rejected")
                .description("Platform API write operations refused by a rate limit (backlog #0-83)")
                .tag(LIMIT_TAG, limit)
                .register(meterRegistry);
    }

    /**
     * Takes one operation from the operator's bucket, then from the shared
     * one. No try/catch: a Redis failure must reach the
     * {@code @CircuitBreaker} proxy to be recorded, and its fallback turns it
     * into {@link Outcome#UNAVAILABLE}.
     */
    @CircuitBreaker(name = "platform-ratelimit", fallbackMethod = "unavailable")
    public Decision tryConsume(UUID operatorUserId) {
        final ConsumptionProbe operator = consume(KEY_PREFIX + operatorUserId, operatorBucket);
        if (!operator.isConsumed()) {
            return limited(operator, rejectedByOperatorLimit, "per-operator", operatorUserId);
        }
        final ConsumptionProbe global = consume(GLOBAL_KEY, globalBucket);
        if (!global.isConsumed()) {
            return limited(global, rejectedByGlobalLimit, "platform-wide", operatorUserId);
        }
        return new Decision(Outcome.ALLOWED, 0);
    }

    private ConsumptionProbe consume(String key, BucketConfiguration configuration) {
        return proxyManager.builder()
                .build(key, () -> configuration)
                .tryConsumeAndReturnRemaining(1);
    }

    private static Decision limited(ConsumptionProbe probe, Counter rejected, String limit, UUID operatorUserId) {
        rejected.increment();
        final long retryAfter = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        log.warn("Platform API operation refused by the {} limit: operator={}, retryAfterSeconds={}",
                limit, operatorUserId, retryAfter);
        return new Decision(Outcome.LIMITED, retryAfter);
    }

    Decision unavailable(UUID operatorUserId, Throwable cause) {
        unavailable.increment();
        if (cause instanceof CallNotPermittedException) {
            // The circuit is open: Redis was not tried, and the failure that
            // opened it was already logged with its stack trace.
            log.warn("Platform API limit unavailable (circuit open) — refusing the operation "
                    + "(fail-closed): operator={}", operatorUserId);
        } else {
            log.error("Platform API limit cannot be checked in Redis — refusing the operation "
                    + "(fail-closed): operator={}", operatorUserId, cause);
        }
        return new Decision(Outcome.UNAVAILABLE, UNAVAILABLE_RETRY_AFTER_SECONDS);
    }
}
