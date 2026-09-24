package com.incidentplatform.notification.client;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.cache.CacheMeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Short-lived, bounded per-tenant cache in front of {@link SlackWorkspaceClientImpl}
 * (backlog #0-21). {@code @Primary}, so every caller that injects
 * {@link SlackWorkspaceClient} gets this one.
 *
 * <h2>Why a cache at all</h2>
 * One routed notification reads the workspace twice — {@code NotificationRouter}
 * (is Slack usable for this tenant?) and {@code SlackNotificationChannel.send()}
 * (token, channel, broadcast flag) — and an incident produces several events in
 * quick succession. A 60-second TTL removes almost all of those round trips while
 * keeping a revoked or rotated token in use for at most a minute.
 *
 * <h2>Why a separate decorator, not a cache inside the HTTP client</h2>
 * The first version cached inside {@link SlackWorkspaceClientImpl} and called its
 * own {@code @Retry}/{@code @CircuitBreaker} method through {@code this}, which
 * bypasses the Spring AOP proxy — the annotations never ran (see that class's
 * Javadoc). The cache has to be checked <em>before</em> the proxy and the HTTP call
 * made <em>behind</em> it, which is exactly a decorator around the proxied bean.
 * It also keeps caching and resilience testable separately.
 *
 * <h2>What is cached</h2>
 * <ul>
 *   <li>A workspace — yes.</li>
 *   <li>"No workspace" (404) — yes: most tenants may never install Slack, and
 *       without it each of their notifications would cost an auth-service call.</li>
 *   <li>{@link SlackWorkspaceLookupUnavailableException} — no, it propagates
 *       uncached: a transient auth-service blip must not keep Slack off for a
 *       whole TTL after auth-service recovers.</li>
 * </ul>
 *
 * <p>Hand-rolled {@link ConcurrentHashMap} rather than {@code @Cacheable}/Caffeine,
 * mirroring {@code ServiceTokenProvider} — the only caching precedent in this
 * codebase; there is no Spring cache abstraction configured in any service.
 * Two concurrent misses for one tenant may both call auth-service; that is
 * harmless (same answer, last write wins) and cheaper than a lock.
 *
 * <h2>Metrics</h2>
 * Exposed through Micrometer's {@link CacheMeterBinder} under the cache name
 * {@value #CACHE_NAME}: {@code cache.gets{result=hit|miss}}, {@code cache.puts},
 * {@code cache.evictions}, {@code cache.size}, plus {@code cache.puts.skipped}
 * (not cached because the map was full of live entries). These are the same
 * names Micrometer registers for a Caffeine cache, deliberately: dashboards and
 * alerts built on them survive the move to Caffeine planned in backlog #0-33
 * unchanged. A miss is any lookup not served from the cache, including one that
 * ended in {@link SlackWorkspaceLookupUnavailableException}; the hit rate
 * answers whether the TTL is worth having, {@code cache.puts.skipped} whether
 * {@link #MAX_CACHED_TENANTS} is too small (before, only a WARN log said so).
 */
@Component
@Primary
public class CachingSlackWorkspaceClient implements SlackWorkspaceClient {

    private static final Logger log =
            LoggerFactory.getLogger(CachingSlackWorkspaceClient.class);

    static final String CACHE_NAME = "slack-workspace";

    static final Duration CACHE_TTL = Duration.ofSeconds(60);

    /** Upper bound on cached tenants, same reasoning as {@code ServiceTokenProvider.MAX_CACHED_TENANTS}. */
    static final int MAX_CACHED_TENANTS = 1_000;

    private record CacheEntry(Optional<SlackWorkspaceInfo> value, Instant expiresAt) {
        boolean isValidAt(Instant now) {
            return now.isBefore(expiresAt);
        }
    }

    private final SlackWorkspaceClient delegate;
    private final Clock clock;
    private final ConcurrentHashMap<String, CacheEntry> cacheByTenant =
            new ConcurrentHashMap<>();

    // LongAdder, not AtomicLong: incremented on every notification from the
    // scheduler thread and the Slack ACK request threads, read only when
    // metrics are scraped — the write-heavy case LongAdder is built for.
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder puts = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final LongAdder skippedPuts = new LongAdder();

    // @Autowired is required, not decorative: with the package-private test
    // constructor below there are two, and Spring refuses to guess — the
    // context would fail to start (caught by SlackWorkspaceClientResilienceTest).
    @Autowired
    public CachingSlackWorkspaceClient(
            // By bean name, not by type: injecting SlackWorkspaceClient by type
            // would resolve to this @Primary bean itself. The injected object is
            // the Resilience4j proxy around SlackWorkspaceClientImpl, which is
            // the whole point of this class existing.
            @Qualifier("slackWorkspaceClientImpl") SlackWorkspaceClient delegate,
            MeterRegistry meterRegistry) {
        this(delegate, Clock.systemUTC(), meterRegistry);
    }

    /** Test seam: lets tests move time forward instead of sleeping past the TTL. */
    CachingSlackWorkspaceClient(SlackWorkspaceClient delegate, Clock clock,
                                MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.clock = clock;
        new Metrics(this).bindTo(meterRegistry);
    }

    @Override
    public Optional<SlackWorkspaceInfo> getWorkspace(String tenantId) {
        final Instant now = clock.instant();
        final CacheEntry cached = cacheByTenant.get(tenantId);
        if (cached != null && cached.isValidAt(now)) {
            hits.increment();
            return cached.value();
        }
        misses.increment();

        // SlackWorkspaceLookupUnavailableException propagates from here without
        // reaching cache() — deliberately not cached, see class Javadoc.
        final Optional<SlackWorkspaceInfo> value = delegate.getWorkspace(tenantId);
        cache(tenantId, value, now);
        return value;
    }

    private void cache(String tenantId, Optional<SlackWorkspaceInfo> value, Instant now) {
        // Fixed (backlog #0-21): expired entries are never removed on read, so
        // without this purge the map fills up with stale tenants and, once it
        // reaches the cap, no new tenant is ever cached again — every
        // notification silently paying two auth-service calls. Purge first,
        // then check the cap: the same order ServiceTokenProvider uses.
        if (cacheByTenant.size() >= MAX_CACHED_TENANTS
                && !cacheByTenant.containsKey(tenantId)) {
            final int before = cacheByTenant.size();
            cacheByTenant.values().removeIf(entry -> !entry.isValidAt(now));
            // Approximate under concurrent writes (size() is), which is fine
            // for a metric; never negative because nothing else removes.
            evictions.add(Math.max(0, before - cacheByTenant.size()));
        }

        if (cacheByTenant.size() < MAX_CACHED_TENANTS
                || cacheByTenant.containsKey(tenantId)) {
            cacheByTenant.put(tenantId, new CacheEntry(value, now.plus(CACHE_TTL)));
            puts.increment();
        } else {
            skippedPuts.increment();
            // More than MAX_CACHED_TENANTS tenants with a live entry inside one
            // TTL: skip caching this one rather than let the map grow without
            // bound. Correct, just uncached.
            log.warn("Slack workspace cache full ({} live tenants) — not caching tenant={}",
                    MAX_CACHED_TENANTS, tenantId);
        }
    }

    /** For tests only. */
    int cachedTenantCount() {
        return cacheByTenant.size();
    }

    /**
     * Micrometer's own base class for custom caches — registers the standard
     * {@code cache.*} meters as function counters/gauges reading the adders
     * above, so the hot path only increments a {@link LongAdder}. It holds the
     * cache through a {@code WeakReference}; this bean is a Spring singleton, so
     * it lives as long as the registry does.
     */
    private static final class Metrics extends CacheMeterBinder<CachingSlackWorkspaceClient> {

        Metrics(CachingSlackWorkspaceClient cache) {
            super(cache, CACHE_NAME, Tags.empty());
        }

        @Override
        protected Long size() {
            final CachingSlackWorkspaceClient cache = getCache();
            return cache == null ? null : (long) cache.cacheByTenant.size();
        }

        @Override
        protected long hitCount() {
            final CachingSlackWorkspaceClient cache = getCache();
            return cache == null ? 0 : cache.hits.sum();
        }

        @Override
        protected Long missCount() {
            final CachingSlackWorkspaceClient cache = getCache();
            return cache == null ? null : cache.misses.sum();
        }

        @Override
        protected Long evictionCount() {
            final CachingSlackWorkspaceClient cache = getCache();
            return cache == null ? null : cache.evictions.sum();
        }

        @Override
        protected long putCount() {
            final CachingSlackWorkspaceClient cache = getCache();
            return cache == null ? 0 : cache.puts.sum();
        }

        @Override
        protected void bindImplementationSpecificMetrics(MeterRegistry registry) {
            // Not a standard cache.* meter: Caffeine evicts instead of refusing a
            // put, so this one goes away with backlog #0-33.
            FunctionCounter.builder("cache.puts.skipped", getCache(),
                            cache -> cache.skippedPuts.sum())
                    .tags(getTagsWithCacheName())
                    .description("Entries not cached because the cache was full of live entries")
                    .register(registry);
        }
    }
}
