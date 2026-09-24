package com.incidentplatform.notification.client;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

@DisplayName("CachingSlackWorkspaceClient")
class CachingSlackWorkspaceClientTest {

    private static final String TENANT_ID = "test-tenant";
    private static final SlackWorkspaceClient.SlackWorkspaceInfo WORKSPACE =
            new SlackWorkspaceClient.SlackWorkspaceInfo("xoxb-tenant", "#incidents", false, null);

    private SlackWorkspaceClient delegate;
    private MutableClock clock;
    private SimpleMeterRegistry meterRegistry;
    private CachingSlackWorkspaceClient client;

    @BeforeEach
    void setUp() {
        delegate = mock(SlackWorkspaceClient.class);
        clock = new MutableClock(Instant.parse("2026-09-24T10:00:00Z"));
        meterRegistry = new SimpleMeterRegistry();
        client = new CachingSlackWorkspaceClient(delegate, clock, meterRegistry);
    }

    @Test
    @DisplayName("a workspace is served from the cache within the TTL")
    void cachesWorkspaceWithinTtl() {
        given(delegate.getWorkspace(TENANT_ID)).willReturn(Optional.of(WORKSPACE));

        client.getWorkspace(TENANT_ID);
        clock.advanceSeconds(59);
        final var second = client.getWorkspace(TENANT_ID);

        assertThat(second).contains(WORKSPACE);
        then(delegate).should(times(1)).getWorkspace(TENANT_ID);
    }

    @Test
    @DisplayName("after the TTL the workspace is fetched again — a revoked token stays in use for at most one TTL")
    void refetchesAfterTtl() {
        given(delegate.getWorkspace(TENANT_ID)).willReturn(Optional.of(WORKSPACE));

        client.getWorkspace(TENANT_ID);
        clock.advanceSeconds(CachingSlackWorkspaceClient.CACHE_TTL.toSeconds());
        client.getWorkspace(TENANT_ID);

        then(delegate).should(times(2)).getWorkspace(TENANT_ID);
    }

    @Test
    @DisplayName("'no workspace' is cached too — Slack-less tenants don't cost a call per notification")
    void cachesNoWorkspace() {
        given(delegate.getWorkspace(TENANT_ID)).willReturn(Optional.empty());

        client.getWorkspace(TENANT_ID);
        final var second = client.getWorkspace(TENANT_ID);

        assertThat(second).isEmpty();
        then(delegate).should(times(1)).getWorkspace(TENANT_ID);
    }

    @Test
    @DisplayName("an outage propagates and is NOT cached — the next call asks auth-service again")
    void doesNotCacheOutage() {
        final var outage = new SlackWorkspaceLookupUnavailableException("down", new RuntimeException());
        given(delegate.getWorkspace(TENANT_ID))
                .willThrow(outage)
                .willReturn(Optional.of(WORKSPACE));

        assertThatThrownBy(() -> client.getWorkspace(TENANT_ID)).isSameAs(outage);
        assertThat(client.getWorkspace(TENANT_ID)).contains(WORKSPACE);

        then(delegate).should(times(2)).getWorkspace(TENANT_ID);
    }

    @Test
    @DisplayName("tenants are cached separately")
    void cachesPerTenant() {
        final var other = new SlackWorkspaceClient.SlackWorkspaceInfo("xoxb-other", null, false, null);
        given(delegate.getWorkspace(TENANT_ID)).willReturn(Optional.of(WORKSPACE));
        given(delegate.getWorkspace("other-tenant")).willReturn(Optional.of(other));

        assertThat(client.getWorkspace(TENANT_ID)).contains(WORKSPACE);
        assertThat(client.getWorkspace("other-tenant")).contains(other);
        assertThat(client.getWorkspace(TENANT_ID)).contains(WORKSPACE);
    }

    @Test
    @DisplayName("at the cap, expired entries are evicted so new tenants are still cached")
    void evictsExpiredEntriesAtCap() {
        given(delegate.getWorkspace(anyString())).willReturn(Optional.empty());
        fillCache();

        clock.advanceSeconds(CachingSlackWorkspaceClient.CACHE_TTL.toSeconds() + 1);
        client.getWorkspace(TENANT_ID);
        client.getWorkspace(TENANT_ID);

        // Before the fix the map stayed full of stale tenants and this tenant was
        // never cached: two calls to auth-service instead of one.
        then(delegate).should(times(1)).getWorkspace(TENANT_ID);
        assertThat(client.cachedTenantCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("at the cap with only live entries, a new tenant is answered correctly but not cached")
    void doesNotGrowPastCap() {
        given(delegate.getWorkspace(anyString())).willReturn(Optional.empty());
        fillCache();

        client.getWorkspace(TENANT_ID);
        client.getWorkspace(TENANT_ID);

        then(delegate).should(times(2)).getWorkspace(TENANT_ID);
        assertThat(client.cachedTenantCount())
                .isEqualTo(CachingSlackWorkspaceClient.MAX_CACHED_TENANTS);
    }

    /**
     * The standard Micrometer cache meter names — the same ones a Caffeine cache
     * registers, which is what lets dashboards survive backlog #0-33.
     */
    @Nested
    @DisplayName("metrics")
    class Metrics {

        @Test
        @DisplayName("a first lookup is a miss and a put, a repeat within the TTL is a hit")
        void countsHitsMissesAndPuts() {
            given(delegate.getWorkspace(TENANT_ID)).willReturn(Optional.of(WORKSPACE));

            client.getWorkspace(TENANT_ID);
            client.getWorkspace(TENANT_ID);
            client.getWorkspace(TENANT_ID);

            assertThat(gets("hit")).isEqualTo(2.0);
            assertThat(gets("miss")).isEqualTo(1.0);
            assertThat(counter("cache.puts")).isEqualTo(1.0);
            assertThat(size()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("an outage counts as a miss and is not a put")
        void outageIsAMissNotAPut() {
            given(delegate.getWorkspace(TENANT_ID))
                    .willThrow(new SlackWorkspaceLookupUnavailableException("down", new RuntimeException()));

            assertThatThrownBy(() -> client.getWorkspace(TENANT_ID))
                    .isInstanceOf(SlackWorkspaceLookupUnavailableException.class);

            assertThat(gets("miss")).isEqualTo(1.0);
            assertThat(counter("cache.puts")).isZero();
            assertThat(size()).isZero();
        }

        @Test
        @DisplayName("expired entries purged at the cap are counted as evictions")
        void countsEvictions() {
            given(delegate.getWorkspace(anyString())).willReturn(Optional.empty());
            fillCache();

            clock.advanceSeconds(CachingSlackWorkspaceClient.CACHE_TTL.toSeconds() + 1);
            client.getWorkspace(TENANT_ID);

            assertThat(counter("cache.evictions"))
                    .isEqualTo(CachingSlackWorkspaceClient.MAX_CACHED_TENANTS);
        }

        @Test
        @DisplayName("a put refused because the cache is full of live entries is counted as skipped")
        void countsSkippedPuts() {
            given(delegate.getWorkspace(anyString())).willReturn(Optional.empty());
            fillCache();

            client.getWorkspace(TENANT_ID);

            assertThat(counter("cache.puts.skipped")).isEqualTo(1.0);
            assertThat(counter("cache.evictions")).isZero();
        }

        private double gets(String result) {
            return meterRegistry.get("cache.gets")
                    .tag("cache", CachingSlackWorkspaceClient.CACHE_NAME)
                    .tag("result", result)
                    .functionCounter().count();
        }

        private double counter(String name) {
            final FunctionCounter counter = meterRegistry.get(name)
                    .tag("cache", CachingSlackWorkspaceClient.CACHE_NAME)
                    .functionCounter();
            return counter.count();
        }

        private double size() {
            final Gauge gauge = meterRegistry.get("cache.size")
                    .tag("cache", CachingSlackWorkspaceClient.CACHE_NAME)
                    .gauge();
            return gauge.value();
        }
    }

    private void fillCache() {
        for (int i = 0; i < CachingSlackWorkspaceClient.MAX_CACHED_TENANTS; i++) {
            client.getWorkspace("tenant-" + i);
        }
        assertThat(client.cachedTenantCount())
                .isEqualTo(CachingSlackWorkspaceClient.MAX_CACHED_TENANTS);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
