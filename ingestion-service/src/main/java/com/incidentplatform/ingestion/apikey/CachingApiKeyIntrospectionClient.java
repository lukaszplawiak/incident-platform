package com.incidentplatform.ingestion.apikey;

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
 * Short-lived cache in front of {@link ApiKeyIntrospectionClientImpl}
 * (backlog #0-16), modelled on notification-service's
 * {@code CachingSlackWorkspaceClient} (backlog #0-21/#0-30).
 *
 * <h2>What is cached, and for how long</h2>
 * <ul>
 *   <li><b>Active keys</b> — {@value #POSITIVE_TTL_SECONDS} s, and never past
 *       the key's own {@code expiresAt} (RFC 7662 §4). This is the revocation
 *       window: a revoked key keeps working here for at most that long.</li>
 *   <li><b>Unknown / revoked / expired keys</b> — {@value #NEGATIVE_TTL_SECONDS} s,
 *       so a sender retrying a wrong key does not turn every retry into a call
 *       to auth-service, while a key created a moment ago works almost at once.</li>
 *   <li><b>Failures are never cached</b>: {@link ApiKeyIntrospectionUnavailableException}
 *       propagates, so the next request asks again.</li>
 * </ul>
 *
 * <h2>Two bounded maps, not one</h2>
 * Keyed by the key's hash. Each map holds at most {@value #MAX_ENTRIES}
 * entries, and they are separate so that a stream of random keys (each a
 * negative entry) cannot push valid keys out of the cache. When a map is full,
 * expired entries are purged first; if it is still full the entry is not cached
 * (correct, just uncached) and counted in {@code cache.puts.skipped}.
 * A generic cache library is backlog #0-33, whose trigger ("a third cache")
 * this is; it stays deferred because its main argument, eviction on revoke,
 * cannot work across services.
 */
@Component
@Primary
public class CachingApiKeyIntrospectionClient implements ApiKeyIntrospectionClient {

    private static final Logger log = LoggerFactory.getLogger(CachingApiKeyIntrospectionClient.class);

    static final long POSITIVE_TTL_SECONDS = 60;
    static final long NEGATIVE_TTL_SECONDS = 5;
    static final int MAX_ENTRIES = 1_000;

    private final ApiKeyIntrospectionClient delegate;
    private final Clock clock;
    private final BoundedTtlCache<IntrospectedApiKey> active;
    private final BoundedTtlCache<Boolean> inactive;

    // @Autowired: two constructors, Spring must be told which one.
    @Autowired
    public CachingApiKeyIntrospectionClient(
            // By bean name: by type this would resolve to this @Primary bean itself.
            @Qualifier("apiKeyIntrospectionClientImpl") ApiKeyIntrospectionClient delegate,
            MeterRegistry meterRegistry) {
        this(delegate, Clock.systemUTC(), meterRegistry);
    }

    /** Test seam: lets tests move time forward instead of sleeping past a TTL. */
    CachingApiKeyIntrospectionClient(ApiKeyIntrospectionClient delegate, Clock clock,
                                     MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.clock = clock;
        this.active = new BoundedTtlCache<>("api-key-introspection", meterRegistry);
        this.inactive = new BoundedTtlCache<>("api-key-introspection-negative", meterRegistry);
    }

    @Override
    public Optional<IntrospectedApiKey> introspect(String keyHash) {
        final Instant now = clock.instant();
        final Optional<IntrospectedApiKey> hit = active.get(keyHash, now);
        if (hit.isPresent()) {
            return hit;
        }
        if (inactive.get(keyHash, now).isPresent()) {
            return Optional.empty();
        }

        // ApiKeyIntrospectionUnavailableException propagates from here without
        // reaching either put() — failures are never cached.
        final Optional<IntrospectedApiKey> answer = delegate.introspect(keyHash);
        if (answer.isPresent()) {
            final Instant until = positiveExpiry(answer.get(), now);
            if (until.isAfter(now)) {
                active.put(keyHash, answer.get(), until, now);
            }
        } else {
            inactive.put(keyHash, Boolean.TRUE, now.plusSeconds(NEGATIVE_TTL_SECONDS), now);
        }
        return answer;
    }

    /**
     * An active key already in the cache, without calling auth-service — lets
     * {@code RemoteApiKeyLookupService} let a known-good key through before the
     * failed-authentication limiter, so an attacker behind the same IP cannot
     * lock out a working integration.
     */
    public Optional<IntrospectedApiKey> findCached(String keyHash) {
        // peek, not get: the introspect() that usually follows counts the hit
        // or miss, and counting here too would report every miss twice.
        return active.peek(keyHash, clock.instant());
    }

    private static Instant positiveExpiry(IntrospectedApiKey key, Instant now) {
        final Instant ttl = now.plusSeconds(POSITIVE_TTL_SECONDS);
        return key.expiresAt() != null && key.expiresAt().isBefore(ttl) ? key.expiresAt() : ttl;
    }

    /** For tests only. */
    int activeSize() {
        return active.size();
    }

    /** For tests only. */
    int inactiveSize() {
        return inactive.size();
    }

    /**
     * A bounded map of entries that expire at their own instant, with the
     * standard Micrometer {@code cache.*} meters (same as
     * {@code CachingSlackWorkspaceClient}'s) plus {@code cache.puts.skipped}.
     */
    static final class BoundedTtlCache<V> {

        private record Entry<V>(V value, Instant expiresAt) { }

        private final String name;
        private final ConcurrentHashMap<String, Entry<V>> entries = new ConcurrentHashMap<>();
        private final LongAdder hits = new LongAdder();
        private final LongAdder misses = new LongAdder();
        private final LongAdder puts = new LongAdder();
        private final LongAdder evictions = new LongAdder();
        private final LongAdder skippedPuts = new LongAdder();

        BoundedTtlCache(String name, MeterRegistry meterRegistry) {
            this.name = name;
            new Metrics<>(this).bindTo(meterRegistry);
        }

        Optional<V> get(String key, Instant now) {
            final Entry<V> entry = entries.get(key);
            if (entry != null && now.isBefore(entry.expiresAt())) {
                hits.increment();
                return Optional.of(entry.value());
            }
            misses.increment();
            return Optional.empty();
        }

        /** Like {@link #get} but without touching the hit/miss meters. */
        Optional<V> peek(String key, Instant now) {
            final Entry<V> entry = entries.get(key);
            return entry != null && now.isBefore(entry.expiresAt())
                    ? Optional.of(entry.value()) : Optional.empty();
        }

        void put(String key, V value, Instant expiresAt, Instant now) {
            if (entries.size() >= MAX_ENTRIES && !entries.containsKey(key)) {
                final int before = entries.size();
                entries.values().removeIf(e -> !now.isBefore(e.expiresAt()));
                evictions.add(Math.max(0, before - entries.size()));
            }
            if (entries.size() < MAX_ENTRIES || entries.containsKey(key)) {
                entries.put(key, new Entry<>(value, expiresAt));
                puts.increment();
            } else {
                skippedPuts.increment();
                log.warn("{} cache full ({} live entries) — not caching this key", name, MAX_ENTRIES);
            }
        }

        int size() {
            return entries.size();
        }

        private static final class Metrics<V> extends CacheMeterBinder<BoundedTtlCache<V>> {

            Metrics(BoundedTtlCache<V> cache) {
                super(cache, cache.name, Tags.empty());
            }

            @Override
            protected Long size() {
                final BoundedTtlCache<V> cache = getCache();
                return cache == null ? null : (long) cache.entries.size();
            }

            @Override
            protected long hitCount() {
                final BoundedTtlCache<V> cache = getCache();
                return cache == null ? 0 : cache.hits.sum();
            }

            @Override
            protected Long missCount() {
                final BoundedTtlCache<V> cache = getCache();
                return cache == null ? null : cache.misses.sum();
            }

            @Override
            protected Long evictionCount() {
                final BoundedTtlCache<V> cache = getCache();
                return cache == null ? null : cache.evictions.sum();
            }

            @Override
            protected long putCount() {
                final BoundedTtlCache<V> cache = getCache();
                return cache == null ? 0 : cache.puts.sum();
            }

            @Override
            protected void bindImplementationSpecificMetrics(MeterRegistry registry) {
                FunctionCounter.builder("cache.puts.skipped", getCache(),
                                cache -> cache.skippedPuts.sum())
                        .tags(getTagsWithCacheName())
                        .description("Entries not cached because the cache was full of live entries")
                        .register(registry);
            }
        }
    }
}
