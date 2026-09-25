package com.incidentplatform.shared.security;

import org.junit.jupiter.api.BeforeEach;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceTokenProvider")
class ServiceTokenProviderTest {

    @Mock
    private JwtUtils jwtUtils;

    private static final String SERVICE_NAME  = "notification-service";
    private static final String TENANT        = "acme-corp";
    private static final String AUD           = ServiceNames.ONCALL_SERVICE;
    private static final Duration SERVICE_TOKEN_TTL = Duration.ofHours(1);
    private static final String FAKE_TOKEN    = "eyJhbGciOiJIUzUxMiJ9.fake.token";

    private ServiceTokenProvider provider;

    @BeforeEach
    void setUp() {
        // lenient: tests that never reach a refresh (e.g. argument validation)
        // do not read the TTL, and strict stubs would flag it as unnecessary
        lenient().when(jwtUtils.getServiceTokenTtl()).thenReturn(SERVICE_TOKEN_TTL);
        provider = new ServiceTokenProvider(jwtUtils, SERVICE_NAME);
    }

    // ─── basic caching ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("token caching")
    class TokenCaching {

        @Test
        @DisplayName("generates token on first call")
        void generatesTokenOnFirstCall() {
            // given
            given(jwtUtils.generateServiceToken(SERVICE_NAME, TENANT, AUD)).willReturn(FAKE_TOKEN);

            // when
            final String token = provider.getToken(TENANT, AUD);

            // then
            assertThat(token).isEqualTo(FAKE_TOKEN);
            then(jwtUtils).should(times(1)).generateServiceToken(SERVICE_NAME, TENANT, AUD);
        }

        @Test
        @DisplayName("caches token on subsequent calls — JwtUtils called only once")
        void cachesTokenOnSubsequentCalls() {
            // given
            given(jwtUtils.generateServiceToken(SERVICE_NAME, TENANT, AUD)).willReturn(FAKE_TOKEN);

            // when
            provider.getToken(TENANT, AUD);
            provider.getToken(TENANT, AUD);
            provider.getToken(TENANT, AUD);

            // then — JwtUtils called only once, token is cached
            then(jwtUtils).should(times(1)).generateServiceToken(SERVICE_NAME, TENANT, AUD);
        }

        @Test
        @DisplayName("returns same token value on repeated calls")
        void returnsSameTokenValue() {
            // given
            given(jwtUtils.generateServiceToken(SERVICE_NAME, TENANT, AUD)).willReturn(FAKE_TOKEN);

            // when
            final String first  = provider.getToken(TENANT, AUD);
            final String second = provider.getToken(TENANT, AUD);

            // then
            assertThat(first).isEqualTo(second).isEqualTo(FAKE_TOKEN);
        }

        @Test
        @DisplayName("caches token — expiration configured in JwtUtils")
        void usesServiceExpirationMs() {
            // TTL is managed by JwtUtils (via JwtProperties.serviceTokenTtl).
            // ServiceTokenProvider reads it via getServiceTokenTtl().
            // This test verifies that caching still holds regardless of TTL value.
            final ServiceTokenProvider shortProvider =
                    new ServiceTokenProvider(jwtUtils, SERVICE_NAME);

            given(jwtUtils.generateServiceToken(SERVICE_NAME, TENANT, AUD)).willReturn(FAKE_TOKEN);

            // when — first call generates token, second call should hit cache
            shortProvider.getToken(TENANT, AUD);
            shortProvider.getToken(TENANT, AUD);

            // then — only one generation regardless of expiration value
            then(jwtUtils).should(times(1)).generateServiceToken(SERVICE_NAME, TENANT, AUD);
        }
    }

    // ─── per-tenant tokens (backlog #0-11) ────────────────────────────────────

    @Nested
    @DisplayName("per-tenant tokens")
    class PerTenantTokens {

        @Test
        @DisplayName("mints a separate token for each tenant")
        void mintsSeparateTokenPerTenant() {
            given(jwtUtils.generateServiceToken(SERVICE_NAME, "tenant-a", AUD)).willReturn("token-a");
            given(jwtUtils.generateServiceToken(SERVICE_NAME, "tenant-b", AUD)).willReturn("token-b");

            assertThat(provider.getToken("tenant-a", AUD)).isEqualTo("token-a");
            assertThat(provider.getToken("tenant-b", AUD)).isEqualTo("token-b");
            // and each is cached independently
            assertThat(provider.getToken("tenant-a", AUD)).isEqualTo("token-a");

            then(jwtUtils).should(times(1)).generateServiceToken(SERVICE_NAME, "tenant-a", AUD);
            then(jwtUtils).should(times(1)).generateServiceToken(SERVICE_NAME, "tenant-b", AUD);
        }

        @Test
        @DisplayName("mints a separate token for each target service")
        void mintsSeparateTokenPerAudience() {
            given(jwtUtils.generateServiceToken(SERVICE_NAME, TENANT, ServiceNames.ONCALL_SERVICE))
                    .willReturn("token-oncall");
            given(jwtUtils.generateServiceToken(SERVICE_NAME, TENANT, ServiceNames.INCIDENT_SERVICE))
                    .willReturn("token-incident");

            assertThat(provider.getToken(TENANT, ServiceNames.ONCALL_SERVICE))
                    .isEqualTo("token-oncall");
            assertThat(provider.getToken(TENANT, ServiceNames.INCIDENT_SERVICE))
                    .isEqualTo("token-incident");
        }

        @Test
        @DisplayName("rejects a null or blank tenantId — a service token always acts for a tenant")
        void rejectsBlankTenant() {
            assertThatThrownBy(() -> provider.getToken(null, AUD))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provider.getToken("  ", AUD))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a tenantId with whitespace, control characters or excessive length")
        void rejectsMalformedTenant() {
            assertThatThrownBy(() -> provider.getToken("acme corp", AUD))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provider.getToken("acme\nX-Injected: 1", AUD))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provider.getToken("t".repeat(101), AUD))
                    .isInstanceOf(IllegalArgumentException.class);
            then(jwtUtils).should(org.mockito.Mockito.never())
                    .generateServiceToken(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("rejects a null or blank audience")
        void rejectsBlankAudience() {
            assertThatThrownBy(() -> provider.getToken(TENANT, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provider.getToken(TENANT, " "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ─── bounded cache (backlog #0-11) ────────────────────────────────────────

    @Nested
    @DisplayName("bounded cache")
    class BoundedCache {

        @Test
        @DisplayName("returns an uncached token once the cache is full of valid entries")
        void returnsUncachedTokenWhenFull() {
            given(jwtUtils.generateServiceToken(eq(SERVICE_NAME), anyString(), eq(AUD)))
                    .willReturn(FAKE_TOKEN);

            for (int i = 0; i < ServiceTokenProvider.MAX_CACHED_TENANTS; i++) {
                provider.getToken("tenant-" + i, AUD);
            }

            // cache is full of valid tokens: the next new tenant still gets a token ...
            assertThat(provider.getToken("overflow", AUD)).isEqualTo(FAKE_TOKEN);
            // ... but it is not cached, so asking again mints again
            provider.getToken("overflow", AUD);
            then(jwtUtils).should(times(2)).generateServiceToken(SERVICE_NAME, "overflow", AUD);

            // while an already cached tenant is still served from the cache
            provider.getToken("tenant-0", AUD);
            then(jwtUtils).should(times(1)).generateServiceToken(SERVICE_NAME, "tenant-0", AUD);
        }
    }

    // ─── thread safety ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("thread safety")
    class ThreadSafety {

        @Test
        @DisplayName("generates token only once under concurrent cold-start load")
        void generatesTokenOnlyOnceUnderConcurrentLoad() throws InterruptedException {
            // given — 20 threads all call getToken(tenant) simultaneously at cold start
            // (no cached token). Only one should trigger generateServiceToken().
            // Previous implementation with two separate volatile fields could
            // generate the token multiple times before double-check kicked in.
            given(jwtUtils.generateServiceToken(SERVICE_NAME, TENANT, AUD)).willReturn(FAKE_TOKEN);

            final int threadCount = 20;
            final CountDownLatch startLatch  = new CountDownLatch(1);
            final CountDownLatch doneLatch   = new CountDownLatch(threadCount);
            final Set<String> tokensReturned = ConcurrentHashMap.newKeySet();

            final ExecutorService executor =
                    Executors.newFixedThreadPool(threadCount);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await(); // synchronize all threads at start
                        tokensReturned.add(provider.getToken(TENANT, AUD));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // when — release all threads simultaneously
            startLatch.countDown();
            assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // then — all 20 threads got the same token
            assertThat(tokensReturned).hasSize(1).containsExactly(FAKE_TOKEN);

            // and — JwtUtils was called exactly once despite 20 concurrent requests
            // (double-check in synchronized refreshAndGet() prevents multiple generations)
            then(jwtUtils).should(times(1)).generateServiceToken(SERVICE_NAME, TENANT, AUD);
        }

        /**
         * Fixed: previously used a plain {@code new ArrayList<>()} as
         * {@code results}, written to concurrently by 10 threads via
         * {@code results.add(...)} with no synchronization —
         * {@code ArrayList} is not thread-safe, and concurrent unsynchronized
         * {@code add()} calls race on its internal size field and
         * array-resize logic, silently dropping elements (a classic Java
         * concurrency pitfall). This was a bug in the test harness itself,
         * not in {@code ServiceTokenProvider} (which is correctly
         * thread-safe — see its own Javadoc on {@code AtomicReference} +
         * double-checked {@code synchronized refreshAndGet()}) — but it
         * made this test flaky: non-deterministic, timing-dependent element
         * loss that may not reproduce locally but did on CI (observed:
         * "expected size 10 but was 9"). Fixed by using
         * {@code Collections.synchronizedList(...)}, matching the
         * thread-safe collection already correctly used in the sibling test
         * {@link #generatesTokenOnlyOnceUnderConcurrentLoad} just above
         * (which used {@code ConcurrentHashMap.newKeySet()}) — this test
         * needed an ordered/duplicate-preserving collection instead (it
         * asserts {@code hasSize(threadCount)} before collapsing to a Set),
         * so a synchronized {@code List} is the closer match here rather
         * than reusing the exact same {@code Set} approach.
         */
        @Test
        @DisplayName("all threads receive the same valid token")
        void allThreadsReceiveSameToken() throws InterruptedException {
            // given
            given(jwtUtils.generateServiceToken(SERVICE_NAME, TENANT, AUD)).willReturn(FAKE_TOKEN);

            final int threadCount = 10;
            final List<String> results =
                    java.util.Collections.synchronizedList(new ArrayList<>());
            final CountDownLatch latch = new CountDownLatch(threadCount);
            final ExecutorService executor =
                    Executors.newFixedThreadPool(threadCount);

            // when
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        results.add(provider.getToken(TENANT, AUD));
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // then — every thread got the same token, no null results
            assertThat(results).hasSize(threadCount);
            assertThat(results).doesNotContainNull();
            assertThat(Set.copyOf(results)).hasSize(1);
        }
    }

    // ─── purpose tokens (backlog #0-16) ───────────────────────────────────────

    @Nested
    @DisplayName("purpose tokens")
    class PurposeTokens {

        private static final String PURPOSE = TokenPurposes.API_KEY_INTROSPECTION;
        private static final String AUTH = ServiceNames.AUTH_SERVICE;

        @Test
        @DisplayName("mints a purpose token once and serves it from the cache")
        void cachesPurposeToken() {
            given(jwtUtils.generatePurposeToken(SERVICE_NAME, PURPOSE, AUTH))
                    .willReturn(FAKE_TOKEN);

            assertThat(provider.getPurposeToken(PURPOSE, AUTH)).isEqualTo(FAKE_TOKEN);
            assertThat(provider.getPurposeToken(PURPOSE, AUTH)).isEqualTo(FAKE_TOKEN);

            then(jwtUtils).should(times(1)).generatePurposeToken(SERVICE_NAME, PURPOSE, AUTH);
        }

        @Test
        @DisplayName("never mints a tenant-bound service token for a purpose")
        void neverUsesTenantPath() {
            given(jwtUtils.generatePurposeToken(anyString(), anyString(), anyString()))
                    .willReturn(FAKE_TOKEN);

            provider.getPurposeToken(PURPOSE, AUTH);

            then(jwtUtils).should(times(0))
                    .generateServiceToken(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("re-mints once the cached token is inside the refresh buffer")
        void refreshesNearExpiry() {
            // TTL shorter than the 300 s refresh buffer: every cached token is
            // already "expiring", so each call mints a new one
            given(jwtUtils.getServiceTokenTtl()).willReturn(Duration.ofSeconds(60));
            given(jwtUtils.generatePurposeToken(SERVICE_NAME, PURPOSE, AUTH))
                    .willReturn("t1", "t2");

            assertThat(provider.getPurposeToken(PURPOSE, AUTH)).isEqualTo("t1");
            assertThat(provider.getPurposeToken(PURPOSE, AUTH)).isEqualTo("t2");
        }

        @Test
        @DisplayName("rejects a blank purpose or audience")
        void rejectsBlank() {
            assertThatThrownBy(() -> provider.getPurposeToken(" ", AUTH))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provider.getPurposeToken(PURPOSE, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
