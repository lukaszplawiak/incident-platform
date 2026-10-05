package com.incidentplatform.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.shared.observability.ClientFallbackMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link AuthServiceTenantStatusProvider} (backlog #0-82, step 2): the cached
 * status pull, one call per tenant at a time, and what it answers while
 * auth-service cannot.
 */
class AuthServiceTenantStatusProviderTest {

    private static final String BASE = "http://auth-service:8087";
    private static final String URL = BASE + AuthServiceTenantStatusProvider.PATH;
    private static final String TENANT = "acme";
    private static final Duration TTL = Duration.ofSeconds(10);

    private MockRestServiceServer server;
    private ServiceTokenProvider tokens;
    private SimpleMeterRegistry meterRegistry;
    private MutableClock clock;
    private AuthServiceTenantStatusProvider provider;

    @BeforeEach
    void setUp() {
        final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        tokens = mock(ServiceTokenProvider.class);
        given(tokens.getToken(anyString(), eq(ServiceNames.AUTH_SERVICE)))
                .willAnswer(invocation -> "svc-token-" + invocation.getArgument(0));
        meterRegistry = new SimpleMeterRegistry();
        clock = new MutableClock(Instant.parse("2026-10-05T10:00:00Z"));
        provider = withFetcher(AuthServiceTenantStatusProvider.httpFetcher(builder.build(), tokens));
    }

    private AuthServiceTenantStatusProvider withFetcher(AuthServiceTenantStatusProvider.Fetcher fetcher) {
        return new AuthServiceTenantStatusProvider(fetcher, Runnable::run, new ClientFallbackMetrics(meterRegistry),
                new SimpleMeterRegistry(), TTL, AuthServiceTenantStatusProvider.FOLLOWER_WAIT, clock);
    }

    private void answer(String tenant, String access) {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer svc-token-" + tenant))
                .andRespond(withSuccess("{\"access\":\"" + access + "\"}", MediaType.APPLICATION_JSON));
    }

    private double fallbacks(String reason) {
        final var counter = meterRegistry.find(ClientFallbackMetrics.METRIC_NAME)
                .tags("client", AuthServiceTenantStatusProvider.CLIENT, "target", ServiceNames.AUTH_SERVICE,
                        "reason", reason)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("asks auth-service with a service token for the tenant, aud=auth-service")
    void asksWithServiceToken() {
        answer(TENANT, "NONE");

        assertThat(provider.accessOf(TENANT)).isEqualTo(TenantAccess.NONE);

        server.verify();
        then(tokens).should().getToken(TENANT, ServiceNames.AUTH_SERVICE);
    }

    @Test
    @DisplayName("an answer is used for the TTL, then auth-service is asked again")
    void cachedForTtl() {
        answer(TENANT, "READ_ONLY");
        assertThat(provider.accessOf(TENANT)).isEqualTo(TenantAccess.READ_ONLY);

        clock.advance(TTL.minusMillis(1));
        assertThat(provider.accessOf(TENANT)).isEqualTo(TenantAccess.READ_ONLY);
        server.verify();

        server.reset();
        answer(TENANT, "FULL");
        clock.advance(Duration.ofMillis(1));
        assertThat(provider.accessOf(TENANT)).isEqualTo(TenantAccess.FULL);
        server.verify();
    }

    @Test
    @DisplayName("each tenant has its own entry: one tenant's suspension is never another's answer")
    void entriesPerTenant() {
        answer("acme", "NONE");
        answer("globex", "FULL");

        assertThat(provider.accessOf("acme")).isEqualTo(TenantAccess.NONE);
        assertThat(provider.accessOf("globex")).isEqualTo(TenantAccess.FULL);
        assertThat(provider.accessOf("acme")).as("from the cache").isEqualTo(TenantAccess.NONE);
        assertThat(provider.accessOf("globex")).as("from the cache").isEqualTo(TenantAccess.FULL);
        server.verify();
    }

    @Test
    @DisplayName("parses what auth-service's controller writes: the TenantStatusResponse JSON round trip")
    void wireFormat() throws Exception {
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final String json = mapper.writeValueAsString(new TenantStatusResponse(TenantAccess.READ_ONLY));

        assertThat(mapper.readValue(json, Map.class)).isEqualTo(Map.of("access", "READ_ONLY"));
        assertThat(mapper.readValue(json, TenantStatusResponse.class).access()).isEqualTo(TenantAccess.READ_ONLY);
    }

    @Nested
    @DisplayName("when auth-service does not answer")
    class Failures {

        @Test
        @DisplayName("the last known status is kept, counted, and not asked again for a TTL")
        void lastKnownKept() {
            answer(TENANT, "NONE");
            provider.accessOf(TENANT);
            server.verify();

            server.reset();
            server.expect(ExpectedCount.once(), requestTo(URL))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
            clock.advance(TTL);

            assertThat(provider.accessOf(TENANT)).isEqualTo(TenantAccess.NONE);
            // Within the next TTL: no second call (the mock would fail on it).
            clock.advance(TTL.minusMillis(1));
            assertThat(provider.accessOf(TENANT)).isEqualTo(TenantAccess.NONE);
            server.verify();
            assertThat(fallbacks("server_error")).isEqualTo(1);
        }

        @Test
        @DisplayName("a known suspension stays a suspension however long auth-service is down (static stability; "
                + "it used to turn into full access after an hour)")
        void knownStatusNeverExpires() {
            answer(TENANT, "NONE");
            provider.accessOf(TENANT);

            server.reset();
            server.expect(ExpectedCount.manyTimes(), requestTo(URL))
                    .andRespond(withException(new IOException("connection refused")));
            for (int i = 0; i < 3; i++) {
                clock.advance(Duration.ofDays(1));
                assertThat(provider.accessOf(TENANT)).isEqualTo(TenantAccess.NONE);
            }
            assertThat(fallbacks("unreachable")).isEqualTo(3);
        }

        @Test
        @DisplayName("a rejected service token (a misconfiguration) keeps the last known status too, counted as "
                + "auth")
        void rejectedTokenKeepsLastKnown() {
            answer(TENANT, "NONE");
            provider.accessOf(TENANT);
            server.reset();
            server.expect(ExpectedCount.manyTimes(), requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

            clock.advance(Duration.ofDays(2));
            assertThat(provider.accessOf(TENANT)).isEqualTo(TenantAccess.NONE);
            assertThat(fallbacks("auth")).isEqualTo(1);
        }

        @Test
        @DisplayName("with no status ever known, FULL (decided fail-open), kept for a TTL; the fallback never "
                + "becomes a known status")
        void unknownIsFull() {
            server.expect(ExpectedCount.twice(), requestTo(URL))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

            assertThat(provider.accessOf(TENANT)).isEqualTo(TenantAccess.FULL);
            assertThat(provider.accessOf(TENANT)).isEqualTo(TenantAccess.FULL);
            clock.advance(TTL);
            assertThat(provider.accessOf(TENANT)).isEqualTo(TenantAccess.FULL);
            server.verify();
        }

        @Test
        @DisplayName("an answer without an access is a failure, not FULL by accident")
        void answerWithoutAccess() {
            answer(TENANT, "NONE");
            provider.accessOf(TENANT);
            server.reset();
            server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
            clock.advance(TTL);

            assertThat(provider.accessOf(TENANT)).isEqualTo(TenantAccess.NONE);
            assertThat(fallbacks("other")).isEqualTo(1);
        }

        @Test
        @DisplayName("after the outage the next answer replaces the kept one")
        void recovers() {
            answer(TENANT, "NONE");
            provider.accessOf(TENANT);
            server.reset();
            server.expect(requestTo(URL)).andRespond(withException(new IOException("refused")));
            clock.advance(TTL);
            provider.accessOf(TENANT);
            server.verify();

            server.reset();
            answer(TENANT, "FULL");
            clock.advance(TTL);
            assertThat(provider.accessOf(TENANT)).isEqualTo(TenantAccess.FULL);
        }
    }

    @Nested
    @DisplayName("one call per tenant at a time (found in review)")
    class SingleFlight {

        private ExecutorService pool;

        @org.junit.jupiter.api.AfterEach
        void stop() {
            if (pool != null) {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("with no entry yet, concurrent requests wait for the one call and all get its answer")
        void coldTenantOneCall() throws Exception {
            final CountDownLatch called = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final AtomicInteger calls = new AtomicInteger();
            final AuthServiceTenantStatusProvider blocking = withFetcher(tenantId -> {
                calls.incrementAndGet();
                called.countDown();
                await(release);
                return TenantAccess.NONE;
            });
            pool = Executors.newFixedThreadPool(8);

            final List<Future<TenantAccess>> answers = new ArrayList<>();
            answers.add(pool.submit(() -> blocking.accessOf(TENANT)));
            assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 7; i++) {
                answers.add(pool.submit(() -> blocking.accessOf(TENANT)));
            }
            release.countDown();

            for (final Future<TenantAccess> answer : answers) {
                assertThat(answer.get(5, TimeUnit.SECONDS)).isEqualTo(TenantAccess.NONE);
            }
            assertThat(calls).hasValue(1);
        }

        @Test
        @DisplayName("with an expired entry, the others get it at once while one request asks (no wait on a slow "
                + "auth-service)")
        void expiredEntryServedDuringRefresh() throws Exception {
            final CountDownLatch called = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final AtomicInteger calls = new AtomicInteger();
            final AuthServiceTenantStatusProvider blocking = withFetcher(tenantId -> {
                if (calls.incrementAndGet() == 2) {
                    called.countDown();
                    await(release);
                }
                return TenantAccess.NONE;
            });
            blocking.accessOf(TENANT);
            clock.advance(TTL);
            pool = Executors.newFixedThreadPool(2);

            final Future<TenantAccess> refreshing = pool.submit(() -> blocking.accessOf(TENANT));
            assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(blocking.accessOf(TENANT)).as("served while the refresh is held").isEqualTo(TenantAccess.NONE);
            assertThat(calls).as("no second call").hasValue(2);

            release.countDown();
            assertThat(refreshing.get(5, TimeUnit.SECONDS)).isEqualTo(TenantAccess.NONE);
        }

        @Test
        @DisplayName("another tenant is not held behind one tenant's call")
        void otherTenantNotHeld() throws Exception {
            final CountDownLatch called = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final AuthServiceTenantStatusProvider blocking = withFetcher(tenantId -> {
                if (tenantId.equals("slow")) {
                    called.countDown();
                    await(release);
                }
                return TenantAccess.FULL;
            });
            pool = Executors.newFixedThreadPool(1);
            final Future<TenantAccess> slow = pool.submit(() -> blocking.accessOf("slow"));
            assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(blocking.accessOf("fast")).isEqualTo(TenantAccess.FULL);
            release.countDown();
            assertThat(slow.get(5, TimeUnit.SECONDS)).isEqualTo(TenantAccess.FULL);
        }

        @Test
        @DisplayName("a call that dies with an Error fails its waiting requests too, never leaves them hanging "
                + "(found by a mutation)")
        void errorDoesNotStrandFollowers() throws Exception {
            final CountDownLatch called = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final AuthServiceTenantStatusProvider dying = withFetcher(tenantId -> {
                called.countDown();
                await(release);
                throw new StackOverflowError("simulated");
            });
            pool = Executors.newFixedThreadPool(2);
            final Future<TenantAccess> leader = pool.submit(() -> dying.accessOf(TENANT));
            assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
            final java.util.concurrent.atomic.AtomicReference<Thread> followerThread =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final Future<TenantAccess> follower = pool.submit(() -> {
                followerThread.set(Thread.currentThread());
                return dying.accessOf(TENANT);
            });
            // Released only once the follower is parked on the leader's future (a bounded wait).
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (followerThread.get() == null || followerThread.get().getState() != Thread.State.TIMED_WAITING) {
                assertThat(System.nanoTime()).as("follower waiting").isLessThan(deadline);
                Thread.sleep(5);
            }
            release.countDown();

            assertThatThrownBy(() -> leader.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(StackOverflowError.class);
            assertThatThrownBy(() -> follower.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class);
        }

        @Test
        @DisplayName("a request waiting on a stuck call gives up after the follower wait and answers FULL, counted")
        void followerWaitBounded() throws Exception {
            final CountDownLatch called = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final AuthServiceTenantStatusProvider stuck = new AuthServiceTenantStatusProvider(tenantId -> {
                called.countDown();
                await(release);
                return TenantAccess.NONE;
            }, Runnable::run, new ClientFallbackMetrics(meterRegistry), new SimpleMeterRegistry(), TTL,
                    Duration.ofMillis(200), clock);
            pool = Executors.newFixedThreadPool(1);
            final Future<TenantAccess> leader = pool.submit(() -> stuck.accessOf(TENANT));
            assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(stuck.accessOf(TENANT)).isEqualTo(TenantAccess.FULL);
            assertThat(fallbacks("other")).isEqualTo(1);
            release.countDown();
            assertThat(leader.get(5, TimeUnit.SECONDS)).isEqualTo(TenantAccess.NONE);
        }

        private static void await(CountDownLatch latch) {
            try {
                assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    @Nested
    @DisplayName("knownAccessOf: never waits on auth-service (found in review)")
    class KnownAccess {

        private final List<Runnable> scheduled = new ArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();
        private AuthServiceTenantStatusProvider deferred;

        @BeforeEach
        void build() {
            deferred = new AuthServiceTenantStatusProvider(tenantId -> {
                calls.incrementAndGet();
                return TenantAccess.NONE;
            }, scheduled::add, new ClientFallbackMetrics(meterRegistry), new SimpleMeterRegistry(), TTL,
                    AuthServiceTenantStatusProvider.FOLLOWER_WAIT, clock);
        }

        @Test
        @DisplayName("a tenant not known yet: FULL at once, a refresh started in the background, the answer seen next")
        void unknownFullThenKnown() {
            assertThat(deferred.knownAccessOf(TENANT)).isEqualTo(TenantAccess.FULL);
            assertThat(calls).as("nothing called on the caller's thread").hasValue(0);
            assertThat(scheduled).hasSize(1);

            scheduled.remove(0).run();
            assertThat(deferred.knownAccessOf(TENANT)).isEqualTo(TenantAccess.NONE);
            assertThat(scheduled).as("fresh: no refresh").isEmpty();
        }

        @Test
        @DisplayName("an expired entry: still its status at once, and a refresh in the background")
        void expiredServedAndRefreshed() {
            deferred.accessOf(TENANT);
            clock.advance(TTL);

            assertThat(deferred.knownAccessOf(TENANT)).isEqualTo(TenantAccess.NONE);
            assertThat(calls).hasValue(1);
            assertThat(scheduled).hasSize(1);
            scheduled.remove(0).run();
            assertThat(calls).hasValue(2);
        }

        @Test
        @DisplayName("a burst of lookups starts one background refresh per tenant, the next only after it ran "
                + "(found in review)")
        void oneRefreshAtATime() {
            deferred.knownAccessOf(TENANT);
            deferred.knownAccessOf(TENANT);
            deferred.knownAccessOf(TENANT);
            deferred.knownAccessOf("other");
            assertThat(scheduled).hasSize(2);

            scheduled.remove(0).run();
            clock.advance(TTL);
            deferred.knownAccessOf(TENANT);
            assertThat(scheduled).as("the first finished: a new one may start").hasSize(2);
        }

        @Test
        @DisplayName("an executor that refuses the task changes nothing for the caller")
        void refusedExecutor() {
            final AuthServiceTenantStatusProvider refusing = new AuthServiceTenantStatusProvider(
                    tenantId -> TenantAccess.NONE, task -> {
                        throw new java.util.concurrent.RejectedExecutionException("full");
                    }, new ClientFallbackMetrics(meterRegistry), new SimpleMeterRegistry(), TTL,
                    AuthServiceTenantStatusProvider.FOLLOWER_WAIT, clock);

            assertThat(refusing.knownAccessOf(TENANT)).isEqualTo(TenantAccess.FULL);
        }
    }

    @Test
    @DisplayName("the cache is bounded: when full of fresh entries a new tenant is answered, not cached")
    void bounded() {
        final AuthServiceTenantStatusProvider bounded = withFetcher(tenantId -> TenantAccess.FULL);

        for (int i = 0; i < AuthServiceTenantStatusProvider.MAX_CACHED_TENANTS; i++) {
            bounded.accessOf("tenant-" + i);
        }
        assertThat(bounded.accessOf("one-more")).isEqualTo(TenantAccess.FULL);
        assertThat(bounded.cachedTenantCount()).isEqualTo(AuthServiceTenantStatusProvider.MAX_CACHED_TENANTS);

        // Past the TTL every entry is expired: purged, and the new tenant fits.
        clock.advance(TTL);
        bounded.accessOf("one-more");
        assertThat(bounded.cachedTenantCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a full cache is purged at most once per TTL, not scanned on every request (found in review)")
    void purgeAtMostOncePerTtl() {
        final AuthServiceTenantStatusProvider bounded = withFetcher(tenantId -> TenantAccess.FULL);
        for (int i = 0; i < AuthServiceTenantStatusProvider.MAX_CACHED_TENANTS; i++) {
            bounded.accessOf("tenant-" + i);
        }
        clock.advance(TTL.dividedBy(2));
        bounded.accessOf("first");     // purges: nothing expired yet; next purge in a TTL

        clock.advance(TTL.dividedBy(2).plusSeconds(1));
        bounded.accessOf("second");    // every entry expired, but too soon for another purge
        assertThat(bounded.cachedTenantCount()).isEqualTo(AuthServiceTenantStatusProvider.MAX_CACHED_TENANTS);

        clock.advance(TTL.dividedBy(2));
        bounded.accessOf("third");     // a TTL after the first purge: purged
        assertThat(bounded.cachedTenantCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a full cache does not purge a known suspension, even expired in an outage (found in review: "
            + "the suspended tenant then got FULL)")
    void fullCacheKeepsKnownSuspension() {
        final java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean();
        final AuthServiceTenantStatusProvider bounded = withFetcher(tenantId -> {
            if (down.get()) {
                throw new org.springframework.web.client.ResourceAccessException("auth-service down");
            }
            return tenantId.equals("suspended") ? TenantAccess.NONE : TenantAccess.FULL;
        });
        bounded.accessOf("suspended");
        for (int i = 1; i < AuthServiceTenantStatusProvider.MAX_CACHED_TENANTS; i++) {
            bounded.accessOf("tenant-" + i);
        }

        down.set(true);
        clock.advance(TTL);
        bounded.accessOf("newcomer");

        assertThat(bounded.cachedTenantCount()).as("expired entries purged, the suspension kept").isEqualTo(2);
        assertThat(bounded.accessOf("suspended")).isEqualTo(TenantAccess.NONE);
    }

    @Test
    @DisplayName("refuses a non-positive TTL")
    void validatesTtl() {
        assertThatThrownBy(() -> new AuthServiceTenantStatusProvider(tenantId -> TenantAccess.FULL, Runnable::run,
                new ClientFallbackMetrics(meterRegistry), meterRegistry, Duration.ZERO,
                AuthServiceTenantStatusProvider.FOLLOWER_WAIT, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A clock the test moves. */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
