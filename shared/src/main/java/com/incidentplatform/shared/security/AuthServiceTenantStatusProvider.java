package com.incidentplatform.shared.security;

import com.incidentplatform.shared.observability.ClientFallbackMetrics;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.cache.CacheMeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;

/**
 * The {@link TenantStatusProvider} of every service but auth-service (backlog
 * #0-82, step 2): asks auth-service, which owns the tenants' status, and keeps
 * the answer for a few seconds.
 *
 * <p>Registered by {@link SharedSecurityAutoConfiguration} when the service sets
 * {@code auth-service.base-url}; auth-service itself answers from its database
 * ({@code TenantAccessService}). It feeds {@link TenantStatusFilter}, so a
 * suspended tenant's access token, which lives up to 15 minutes, and its API
 * keys are refused in every service within {@code tenant-status.cache.ttl}
 * (10 s by default) of the suspension, not when the token expires.
 *
 * <h2>The call</h2>
 * {@code GET /api/v1/internal/tenant-status} with a service token for the
 * tenant asked about and {@code aud=auth-service}, the narrow HTTP pull this
 * platform uses for data auth-service owns (backlog #0-21/#0-30, as
 * notification-service reads a tenant's Slack workspace). The tenant is the
 * token's signed claim: no service can ask about another tenant than the one
 * it acts for. Own connect and read timeouts, short ones: the lookup runs on a
 * request thread.
 *
 * <h2>When auth-service does not answer (decided in the review of step 2)</h2>
 * Every failure is counted ({@link ClientFallbackMetrics}, client
 * {@value #CLIENT}): an outage alerts {@code TenantStatusLookupFailing}, a
 * rejected service token ({@code reason="auth"}, a misconfiguration such as a
 * mismatched JWT secret, which does not heal by itself) alerts
 * {@code TenantStatusLookupRejected} at once. Then:
 * <ul>
 *   <li>the last status auth-service gave, however old: "static stability" —
 *       a service that cannot reach its source of truth keeps working on the
 *       last good answer rather than switching to a default. A suspension stays
 *       a suspension through any outage. Changed from the first version, which
 *       gave full access an hour after the last answer: that abandoned a known
 *       suspension, and with a rejected token it did so for good;</li>
 *   <li>{@link TenantAccess#FULL} only for a tenant this service never had an
 *       answer for: fail-open, as revocation and the Redis rate limits are.
 *       Failing closed would make an auth-service outage a platform-wide outage
 *       of every tenant new to this instance (after a restart: all of them).</li>
 * </ul>
 * Either way the answer is kept for another TTL before the next attempt: a
 * down auth-service costs each tenant one timed-out call per TTL. The cost of
 * keeping a status: a tenant resumed during an outage stays refused here until
 * auth-service answers again — the safe side to be wrong on. The outage is
 * logged at WARN once per tenant when it starts and at INFO when it ends.
 *
 * <h2>One call per tenant at a time (found in review)</h2>
 * Requests of a tenant whose entry expired do not each call auth-service:
 * the first one does, and the others meanwhile get the entry they would have
 * got a moment earlier, or, for a tenant with none yet, wait for that one call.
 * Without it every concurrent request of a busy tenant waited out the timeout
 * in an outage, at each TTL boundary. A waiting request gives up after
 * {@link #FOLLOWER_WAIT} (longer than the call's own timeouts, so only a stuck
 * call reaches it) and answers FULL, counted, as for any tenant with no
 * status known.
 *
 * <h2>Without waiting: {@link #knownAccessOf}</h2>
 * For callers on threads that must not block on auth-service (a STOMP
 * {@code CONNECT} on the message channel's pool, the WebSocket sweep over
 * every connected tenant; found in review: in an outage each cold tenant cost
 * such a thread up to the call's timeouts). It answers from the cache, the
 * last known status even if expired, FULL if none, and starts a refresh on a
 * virtual thread when the entry is missing or expired, so the next look sees
 * the answer.
 *
 * <h2>Cache</h2>
 * Hand-rolled and bounded like {@code CachingSlackWorkspaceClient}
 * ({@value #MAX_CACHED_TENANTS} tenants; when full, expired entries are purged
 * first, at most once per TTL so a full cache does not scan itself on every
 * request (found in review), except a known suspension, which static stability needs to keep
 * through an outage, when every entry is expired (found in review); then the
 * new one is left uncached), with the same Micrometer {@code cache.*} meters
 * under the name {@value #CACHE_NAME}. A generic cache is backlog #0-33.
 */
public class AuthServiceTenantStatusProvider implements TenantStatusProvider {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceTenantStatusProvider.class);

    static final String CLIENT = "tenant-status";
    static final String CACHE_NAME = "tenant-status";
    static final String PATH = "/api/v1/internal/tenant-status";
    /**
     * Upper bound on cached tenants, as {@code ServiceTokenProvider.MAX_CACHED_TENANTS}
     * but higher (raised in review from 1 000): past it every request of an
     * uncached tenant calls auth-service, and an entry is a few dozen bytes.
     */
    static final int MAX_CACHED_TENANTS = 10_000;
    /** How long a request waits for another request's call; above the client's connect + read timeouts. */
    static final java.time.Duration FOLLOWER_WAIT = java.time.Duration.ofSeconds(5);

    /** Asks auth-service about one tenant; a seam so tests can hold a call open. */
    @FunctionalInterface
    interface Fetcher {
        TenantStatusResponse fetch(String tenantId);
    }

    /**
     * One tenant's entry.
     *
     * @param access       what the provider answers
     * @param known        {@code access} is auth-service's answer, not the FULL
     *                     given to a tenant it never answered for
     * @param since        when the tenant's suspension began, as auth-service said
     *                     ({@code null} for full access or when it did not say)
     * @param refreshAfter when to ask auth-service again
     * @param failing      the last attempt failed (for logging the outage once)
     */
    private record Entry(TenantAccess access, boolean known, Instant since, Instant refreshAfter,
                         boolean failing) {

        boolean isFreshAt(Instant now) {
            return now.isBefore(refreshAfter);
        }

        /** A known suspension: never purged to make room, or an outage would turn it into FULL. */
        boolean isKnownRestriction() {
            return known && access != TenantAccess.FULL;
        }
    }

    private final Fetcher fetcher;
    private final Executor backgroundRefresh;
    private final ClientFallbackMetrics fallbackMetrics;
    private final Duration ttl;
    private final Duration followerWait;
    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<TenantAccess>> inFlight = new ConcurrentHashMap<>();
    /** Tenants with a background refresh started and not finished: one at a time per tenant. */
    private final java.util.Set<String> refreshing = ConcurrentHashMap.newKeySet();
    /** When a full cache may next be purged. */
    private volatile Instant nextPurge = Instant.MIN;

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder puts = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final LongAdder skippedPuts = new LongAdder();

    /**
     * @param restClient a client whose base URL is auth-service's and which has
     *                   connect and read timeouts set
     * @param ttl        how long an answer is used before asking again
     */
    public AuthServiceTenantStatusProvider(RestClient restClient, ServiceTokenProvider serviceTokenProvider,
                                           ClientFallbackMetrics fallbackMetrics, MeterRegistry meterRegistry,
                                           Duration ttl) {
        this(httpFetcher(restClient, serviceTokenProvider),
                task -> Thread.ofVirtual().name("tenant-status-refresh").start(task),
                fallbackMetrics, meterRegistry, ttl, FOLLOWER_WAIT, Clock.systemUTC());
    }

    /**
     * Test seam: a fetcher tests control, the executor of background refreshes,
     * and a clock they move instead of sleeping past a TTL.
     */
    AuthServiceTenantStatusProvider(Fetcher fetcher, Executor backgroundRefresh, ClientFallbackMetrics fallbackMetrics,
                                    MeterRegistry meterRegistry, Duration ttl, Duration followerWait, Clock clock) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.backgroundRefresh = Objects.requireNonNull(backgroundRefresh, "backgroundRefresh");
        this.fallbackMetrics = Objects.requireNonNull(fallbackMetrics, "fallbackMetrics");
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("tenant-status.cache.ttl must be a positive duration: " + ttl);
        }
        this.ttl = ttl;
        this.followerWait = Objects.requireNonNull(followerWait, "followerWait");
        this.clock = Objects.requireNonNull(clock, "clock");
        new Metrics(this).bindTo(meterRegistry);
    }

    static Fetcher httpFetcher(RestClient restClient, ServiceTokenProvider serviceTokenProvider) {
        Objects.requireNonNull(restClient, "restClient");
        Objects.requireNonNull(serviceTokenProvider, "serviceTokenProvider");
        return tenantId -> {
            final TenantStatusResponse response = restClient.get()
                    .uri(PATH)
                    .header("Authorization",
                            "Bearer " + serviceTokenProvider.getToken(tenantId, ServiceNames.AUTH_SERVICE))
                    .retrieve()
                    .body(TenantStatusResponse.class);
            if (response == null || response.access() == null) {
                throw new IllegalStateException("auth-service answered the tenant status lookup without an access");
            }
            return response;
        };
    }

    @Override
    public TenantAccess accessOf(String tenantId) {
        final Entry cached = entries.get(tenantId);
        if (cached != null && cached.isFreshAt(clock.instant())) {
            hits.increment();
            return cached.access();
        }
        misses.increment();

        final CompletableFuture<TenantAccess> mine = new CompletableFuture<>();
        final CompletableFuture<TenantAccess> running = inFlight.putIfAbsent(tenantId, mine);
        if (running != null) {
            // Another request of this tenant is asking already: answer what it
            // would have been answered a moment ago, or wait for that call.
            return cached != null ? cached.access() : await(tenantId, running);
        }
        try {
            // A call that finished between the read above and taking the lead
            // has just refreshed the entry: use it, do not ask again.
            final Entry current = entries.get(tenantId);
            if (current != null && current.isFreshAt(clock.instant())) {
                mine.complete(current.access());
                return current.access();
            }
            final TenantAccess access = refresh(tenantId, current);
            mine.complete(access);
            return access;
        } catch (RuntimeException | Error e) {
            // refresh() answers every failure of the call; this is a defect or
            // the JVM's own Error (found by a mutation: a follower waited for
            // ever on a future nobody completed). The followers fail with it,
            // rather than hang or get an answer nobody gave.
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(tenantId, mine);
        }
    }

    @Override
    public TenantAccess knownAccessOf(String tenantId) {
        final Entry cached = entries.get(tenantId);
        if (cached == null || !cached.isFreshAt(clock.instant())) {
            refreshInBackground(tenantId);
        }
        return cached == null ? TenantAccess.FULL : cached.access();
    }

    /**
     * The answer {@link #accessOf} gives, with its suspension time, if it is
     * auth-service's (now, or the last one before an outage), never the FULL
     * given to a tenant it never answered for (backlog #0-82, step 2b). Empty
     * too for a tenant whose answer could not be cached ({@code
     * MAX_CACHED_TENANTS}): the caller then keeps its state, which is the safe
     * side either way.
     */
    @Override
    public Optional<TenantAccessState> confirmedStateOf(String tenantId) {
        accessOf(tenantId);
        final Entry entry = entries.get(tenantId);
        return entry != null && entry.known()
                ? Optional.of(new TenantAccessState(entry.access(), entry.since()))
                : Optional.empty();
    }

    private void refreshInBackground(String tenantId) {
        // Claimed atomically (found in review: a check of inFlight let a burst of
        // CONNECTs start a thread each before the first registered its call).
        if (!refreshing.add(tenantId)) {
            return;
        }
        try {
            backgroundRefresh.execute(() -> {
                try {
                    accessOf(tenantId);
                } catch (RuntimeException e) {
                    log.error("Background tenant status refresh failed: tenant={}, error={}", tenantId,
                            e.getClass().getSimpleName());
                } finally {
                    refreshing.remove(tenantId);
                }
            });
        } catch (RuntimeException e) {
            refreshing.remove(tenantId);
            // Never more than the caller's answer is at stake: the next look retries.
            log.error("Background tenant status refresh not started: tenant={}, error={}", tenantId,
                    e.getClass().getSimpleName());
        }
    }

    /** A follower's wait for the leader's call, bounded (found in review). */
    private TenantAccess await(String tenantId, CompletableFuture<TenantAccess> running) {
        try {
            return running.get(followerWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            fallbackMetrics.record(CLIENT, ServiceNames.AUTH_SERVICE, e);
            log.warn("Waited {} for another request's tenant status lookup, answering FULL: tenant={}",
                    followerWait, tenantId);
            return TenantAccess.FULL;
        } catch (ExecutionException e) {
            // The leader died of a defect or an Error: fail with the same.
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the tenant status", e);
        }
    }

    private TenantAccess refresh(String tenantId, Entry cached) {
        final TenantStatusResponse answer;
        try {
            answer = fetcher.fetch(tenantId);
        } catch (RuntimeException e) {
            return fallBack(tenantId, cached, e);
        }
        if (cached != null && cached.failing()) {
            log.info("Tenant status lookup works again: tenant={}", tenantId);
        }
        final Instant since = answer.access() == TenantAccess.FULL ? null : answer.since();
        put(tenantId, new Entry(answer.access(), true, since, clock.instant().plus(ttl), false));
        return answer.access();
    }

    private TenantAccess fallBack(String tenantId, Entry cached, RuntimeException e) {
        fallbackMetrics.record(CLIENT, ServiceNames.AUTH_SERVICE, e);
        final boolean known = cached != null && cached.known();
        final TenantAccess access = known ? cached.access() : TenantAccess.FULL;
        if (cached == null || !cached.failing()) {
            // The exception's type only: a message could carry a response body.
            log.warn("Tenant status lookup failed, answering {} until auth-service answers again: "
                            + "tenant={}, error={}",
                    known ? "the last known status " + access : "FULL (no status known)",
                    tenantId, e.getClass().getSimpleName());
        }
        put(tenantId, new Entry(access, known, known ? cached.since() : null, clock.instant().plus(ttl), true));
        return access;
    }

    private void put(String tenantId, Entry entry) {
        final Instant now = clock.instant();
        if (entries.size() >= MAX_CACHED_TENANTS && !entries.containsKey(tenantId) && !now.isBefore(nextPurge)) {
            nextPurge = now.plus(ttl);
            final int before = entries.size();
            // An expired entry is asked about again on its next use anyway, but
            // a known suspension is kept: in an outage it is the only answer.
            entries.values().removeIf(e -> !e.isFreshAt(now) && !e.isKnownRestriction());
            evictions.add(Math.max(0, before - entries.size()));
        }
        if (entries.size() < MAX_CACHED_TENANTS || entries.containsKey(tenantId)) {
            entries.put(tenantId, entry);
            puts.increment();
        } else {
            // Correct, just uncached: the next request of this tenant asks again.
            skippedPuts.increment();
        }
    }

    /** For tests only. */
    int cachedTenantCount() {
        return entries.size();
    }

    /** The standard {@code cache.*} meters, as {@code CachingSlackWorkspaceClient} registers them. */
    private static final class Metrics extends CacheMeterBinder<AuthServiceTenantStatusProvider> {

        Metrics(AuthServiceTenantStatusProvider cache) {
            super(cache, CACHE_NAME, Tags.empty());
        }

        @Override
        protected Long size() {
            final AuthServiceTenantStatusProvider cache = getCache();
            return cache == null ? null : (long) cache.entries.size();
        }

        @Override
        protected long hitCount() {
            final AuthServiceTenantStatusProvider cache = getCache();
            return cache == null ? 0 : cache.hits.sum();
        }

        @Override
        protected Long missCount() {
            final AuthServiceTenantStatusProvider cache = getCache();
            return cache == null ? null : cache.misses.sum();
        }

        @Override
        protected Long evictionCount() {
            final AuthServiceTenantStatusProvider cache = getCache();
            return cache == null ? null : cache.evictions.sum();
        }

        @Override
        protected long putCount() {
            final AuthServiceTenantStatusProvider cache = getCache();
            return cache == null ? 0 : cache.puts.sum();
        }

        @Override
        protected void bindImplementationSpecificMetrics(MeterRegistry registry) {
            FunctionCounter.builder("cache.puts.skipped", getCache(), cache -> cache.skippedPuts.sum())
                    .tags(getTagsWithCacheName())
                    .description("Entries not cached because the cache was full of fresh entries")
                    .register(registry);
        }
    }
}
