package com.incidentplatform.auth.ratelimit;

import com.incidentplatform.auth.repository.ApiKeyRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * How many API keys one user may create per hour (backlog #0-89, found in
 * review), integrations' keys included.
 *
 * <h2>Why</h2>
 * Every key created emails the account, one email per key, never merged:
 * OWASP ASVS 2.2.3 asks for a notice per change, and a merged notice let a key
 * made right after the owner's own hide behind it. The limit on the action is
 * what keeps a session holder from flooding the owner's mailbox (and burying
 * the warnings) by creating and revoking keys in a loop; the caps on active
 * keys do not, as revoked keys free their place.
 *
 * <h2>Counted in the database, not Redis</h2>
 * V26 records who created each key and when, so the keys a user created in the
 * last hour are a count on an index ({@code idx_api_keys_created_by}), revoked
 * ones included. Nothing to be unavailable, so no fail-open or fail-closed
 * choice, unlike the Redis limiters. The callers lock the creator's row first
 * ({@link #lockingCreator}, NOWAIT), so one user's parallel requests cannot
 * all pass a read-then-insert check (found in review: 200 parallel requests
 * did); the one that finds the row locked gets a 429 instead of waiting.
 *
 * <p>Loads at most {@code perUserPerHour} creation times, newest first: if
 * there are that many, the oldest of them leaving the window is when the next
 * key may be made.
 */
@Component
public class ApiKeyCreationLimit {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyCreationLimit.class);

    static final Duration WINDOW = Duration.ofHours(1);

    /** Retry-After when another creation of the same user holds the lock: it takes milliseconds. */
    static final long BUSY_RETRY_AFTER_SECONDS = 1;

    private final ApiKeyRepository apiKeyRepository;
    private final int perUserPerHour;
    private final Clock clock;
    private final Counter limited;

    /** For Spring; the package-private one takes a clock for tests. */
    @Autowired
    public ApiKeyCreationLimit(ApiKeyRepository apiKeyRepository,
                               @Value("${api-keys.creation-limit.per-user-per-hour:20}") int perUserPerHour,
                               MeterRegistry meterRegistry) {
        this(apiKeyRepository, perUserPerHour, meterRegistry, Clock.systemUTC());
    }

    ApiKeyCreationLimit(ApiKeyRepository apiKeyRepository, int perUserPerHour,
                        MeterRegistry meterRegistry, Clock clock) {
        if (perUserPerHour < 1) {
            throw new IllegalArgumentException(
                    "api-keys.creation-limit.per-user-per-hour must be at least 1, was " + perUserPerHour);
        }
        this.apiKeyRepository = apiKeyRepository;
        this.perUserPerHour = perUserPerHour;
        this.clock = clock;
        this.limited = Counter.builder("auth.api_key.creation.limited")
                .description("API key creations refused by the per-user hourly limit (backlog #0-89)")
                .register(meterRegistry);
    }

    /**
     * Runs the creator's row lock ({@code UserRepository.findByIdAndTenantIdForUpdate},
     * NOWAIT) and turns "another creation of this user holds it" into a 429
     * with a one-second Retry-After (backlog #0-89, review): a waiting
     * request would hold a pooled connection, so parallel requests of one user
     * are refused rather than queued. A user creating keys one at a time never
     * sees it.
     */
    public static <T> T lockingCreator(Supplier<T> lock) {
        try {
            return lock.get();
        } catch (PessimisticLockingFailureException busy) {
            log.info("API key creation refused: another creation of the same user is in progress");
            throw new RateLimitRefusedException(
                    new RateLimitDecision(RateLimitDecision.Outcome.LIMITED, BUSY_RETRY_AFTER_SECONDS));
        }
    }

    /**
     * Whether the user may create one more key now. Refused: LIMITED with the
     * seconds until the oldest key of the window leaves it.
     */
    public RateLimitDecision check(String tenantId, UUID userId) {
        final Instant now = clock.instant();
        final List<Instant> newest = apiKeyRepository.findCreationTimesSince(
                tenantId, userId, now.minus(WINDOW), PageRequest.of(0, perUserPerHour));
        if (newest.size() < perUserPerHour) {
            return RateLimitDecision.ALLOWED;
        }
        limited.increment();
        final Instant freed = newest.get(perUserPerHour - 1).plus(WINDOW);
        final long retryAfter = Math.max(1, Duration.between(now, freed).toSeconds() + 1);
        log.warn("API key creation refused by the hourly limit: user={}, tenant={}, limit={}, "
                + "retryAfterSeconds={}", userId, tenantId, perUserPerHour, retryAfter);
        return new RateLimitDecision(RateLimitDecision.Outcome.LIMITED, retryAfter);
    }
}
