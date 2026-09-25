package com.incidentplatform.ingestion.apikey;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Tests for {@link CachingApiKeyIntrospectionClient}: what is cached, for how
 * long (the revocation window), and that failures never are. Time is moved
 * with a mutable {@link Clock} instead of sleeping past a TTL.
 */
@DisplayName("CachingApiKeyIntrospectionClient")
class CachingApiKeyIntrospectionClientTest {

    private static final String HASH = "h".repeat(64);
    private static final Instant T0 = Instant.parse("2026-09-25T10:00:00Z");

    private ApiKeyIntrospectionClient delegate;
    private MutableClock clock;
    private SimpleMeterRegistry meterRegistry;
    private CachingApiKeyIntrospectionClient cache;

    @BeforeEach
    void setUp() {
        delegate = mock(ApiKeyIntrospectionClient.class);
        clock = new MutableClock(T0);
        meterRegistry = new SimpleMeterRegistry();
        cache = new CachingApiKeyIntrospectionClient(delegate, clock, meterRegistry);
    }

    private static IntrospectedApiKey key(Instant expiresAt) {
        return new IntrospectedApiKey(UUID.randomUUID(), "acme", null,
                List.of("alerts:ingest"), expiresAt);
    }

    @Nested
    @DisplayName("active keys")
    class ActiveKeys {

        @Test
        @DisplayName("are served from cache within the positive TTL")
        void cachedWithinTtl() {
            final IntrospectedApiKey key = key(null);
            given(delegate.introspect(HASH)).willReturn(Optional.of(key));

            cache.introspect(HASH);
            clock.advance(Duration.ofSeconds(CachingApiKeyIntrospectionClient.POSITIVE_TTL_SECONDS - 1));

            assertThat(cache.introspect(HASH)).contains(key);
            then(delegate).should(times(1)).introspect(HASH);
        }

        @Test
        @DisplayName("are asked again once the positive TTL has passed — the revocation window")
        void refreshedAfterTtl() {
            given(delegate.introspect(HASH)).willReturn(Optional.of(key(null)), Optional.empty());

            cache.introspect(HASH);
            clock.advance(Duration.ofSeconds(CachingApiKeyIntrospectionClient.POSITIVE_TTL_SECONDS));

            assertThat(cache.introspect(HASH)).isEmpty();
            then(delegate).should(times(2)).introspect(HASH);
        }

        @Test
        @DisplayName("are never cached past the key's own expiresAt (RFC 7662 §4)")
        void cappedAtExpiresAt() {
            given(delegate.introspect(HASH))
                    .willReturn(Optional.of(key(T0.plusSeconds(10))), Optional.empty());

            cache.introspect(HASH);
            clock.advance(Duration.ofSeconds(10));

            assertThat(cache.introspect(HASH)).isEmpty();
            then(delegate).should(times(2)).introspect(HASH);
        }

        @Test
        @DisplayName("an answer that has already expired is returned but not cached")
        void alreadyExpiredNotCached() {
            given(delegate.introspect(HASH)).willReturn(Optional.of(key(T0.minusSeconds(1))));

            assertThat(cache.introspect(HASH)).isPresent();
            assertThat(cache.activeSize()).isZero();
        }

        @Test
        @DisplayName("findCached sees a cached key without calling auth-service or counting a hit")
        void findCachedPeeks() {
            final IntrospectedApiKey key = key(null);
            given(delegate.introspect(HASH)).willReturn(Optional.of(key));
            cache.introspect(HASH);

            assertThat(cache.findCached(HASH)).contains(key);
            assertThat(cache.findCached("unknown")).isEmpty();
            then(delegate).should(times(1)).introspect(anyString());
            assertThat(meterRegistry.get("cache.gets").tag("cache", "api-key-introspection")
                    .tag("result", "hit").functionCounter().count()).isZero();
        }
    }

    @Nested
    @DisplayName("inactive keys")
    class InactiveKeys {

        @Test
        @DisplayName("are cached for the short negative TTL only")
        void negativeTtl() {
            given(delegate.introspect(HASH)).willReturn(Optional.empty());

            cache.introspect(HASH);
            cache.introspect(HASH);
            then(delegate).should(times(1)).introspect(HASH);

            clock.advance(Duration.ofSeconds(CachingApiKeyIntrospectionClient.NEGATIVE_TTL_SECONDS));
            cache.introspect(HASH);
            then(delegate).should(times(2)).introspect(HASH);
        }

        @Test
        @DisplayName("go to a separate map, so random keys cannot push valid keys out")
        void separateMaps() {
            given(delegate.introspect(anyString())).willReturn(Optional.empty());
            given(delegate.introspect(HASH)).willReturn(Optional.of(key(null)));

            cache.introspect(HASH);
            for (int i = 0; i < CachingApiKeyIntrospectionClient.MAX_ENTRIES + 5; i++) {
                cache.introspect("random-" + i);
            }

            assertThat(cache.activeSize()).isEqualTo(1);
            assertThat(cache.inactiveSize()).isEqualTo(CachingApiKeyIntrospectionClient.MAX_ENTRIES);
            assertThat(cache.findCached(HASH)).isPresent();
            assertThat(meterRegistry.get("cache.puts.skipped")
                    .tag("cache", "api-key-introspection-negative")
                    .functionCounter().count()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("a full map is purged of expired entries before a put is skipped")
        void purgesExpiredWhenFull() {
            given(delegate.introspect(anyString())).willReturn(Optional.empty());
            for (int i = 0; i < CachingApiKeyIntrospectionClient.MAX_ENTRIES; i++) {
                cache.introspect("old-" + i);
            }
            clock.advance(Duration.ofSeconds(CachingApiKeyIntrospectionClient.NEGATIVE_TTL_SECONDS));

            cache.introspect("new");

            assertThat(cache.inactiveSize()).isEqualTo(1);
            assertThat(meterRegistry.get("cache.evictions")
                    .tag("cache", "api-key-introspection-negative")
                    .functionCounter().count())
                    .isEqualTo(CachingApiKeyIntrospectionClient.MAX_ENTRIES);
        }
    }

    @Nested
    @DisplayName("failures")
    class Failures {

        @Test
        @DisplayName("are never cached: the exception propagates and the next call asks again")
        void notCached() {
            given(delegate.introspect(HASH))
                    .willThrow(new ApiKeyIntrospectionUnavailableException("down", null))
                    .willReturn(Optional.of(key(null)));

            assertThatThrownBy(() -> cache.introspect(HASH))
                    .isInstanceOf(ApiKeyIntrospectionUnavailableException.class);
            assertThat(cache.activeSize()).isZero();
            assertThat(cache.inactiveSize()).isZero();

            assertThat(cache.introspect(HASH)).isPresent();
        }
    }

    @Test
    @DisplayName("an empty cache never calls the delegate from findCached")
    void findCachedOnEmptyCache() {
        assertThat(cache.findCached(HASH)).isEmpty();
        then(delegate).should(never()).introspect(anyString());
    }

    /** A clock tests can move forward. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
