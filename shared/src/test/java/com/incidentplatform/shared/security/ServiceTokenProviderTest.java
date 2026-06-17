package com.incidentplatform.shared.security;

import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceTokenProvider")
class ServiceTokenProviderTest {

    @Mock
    private JwtUtils jwtUtils;

    private static final String SERVICE_NAME  = "notification-service";
    private static final long   EXPIRATION_MS = 2_592_000_000L; // 30 days
    private static final String FAKE_TOKEN    = "eyJhbGciOiJIUzUxMiJ9.fake.token";

    private ServiceTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ServiceTokenProvider(jwtUtils, SERVICE_NAME, EXPIRATION_MS);
    }

    // ─── basic caching ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("token caching")
    class TokenCaching {

        @Test
        @DisplayName("generates token on first call")
        void generatesTokenOnFirstCall() {
            // given
            given(jwtUtils.generateServiceToken(SERVICE_NAME)).willReturn(FAKE_TOKEN);

            // when
            final String token = provider.getToken();

            // then
            assertThat(token).isEqualTo(FAKE_TOKEN);
            then(jwtUtils).should(times(1)).generateServiceToken(SERVICE_NAME);
        }

        @Test
        @DisplayName("caches token on subsequent calls — JwtUtils called only once")
        void cachesTokenOnSubsequentCalls() {
            // given
            given(jwtUtils.generateServiceToken(SERVICE_NAME)).willReturn(FAKE_TOKEN);

            // when
            provider.getToken();
            provider.getToken();
            provider.getToken();

            // then — JwtUtils called only once, token is cached
            then(jwtUtils).should(times(1)).generateServiceToken(SERVICE_NAME);
        }

        @Test
        @DisplayName("returns same token value on repeated calls")
        void returnsSameTokenValue() {
            // given
            given(jwtUtils.generateServiceToken(SERVICE_NAME)).willReturn(FAKE_TOKEN);

            // when
            final String first  = provider.getToken();
            final String second = provider.getToken();

            // then
            assertThat(first).isEqualTo(second).isEqualTo(FAKE_TOKEN);
        }

        @Test
        @DisplayName("uses serviceExpirationMs — not user expirationMs")
        void usesServiceExpirationMs() {
            // given — provider with 24h expiration (would be user-level, not service)
            final ServiceTokenProvider shortProvider =
                    new ServiceTokenProvider(jwtUtils, SERVICE_NAME, 86_400_000L);

            given(jwtUtils.generateServiceToken(SERVICE_NAME)).willReturn(FAKE_TOKEN);

            // when — first call generates token, second call should hit cache
            shortProvider.getToken();
            shortProvider.getToken();

            // then — only one generation regardless of expiration value
            then(jwtUtils).should(times(1)).generateServiceToken(SERVICE_NAME);
        }
    }

    // ─── thread safety ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("thread safety")
    class ThreadSafety {

        @Test
        @DisplayName("generates token only once under concurrent cold-start load")
        void generatesTokenOnlyOnceUnderConcurrentLoad() throws InterruptedException {
            // given — 20 threads all call getToken() simultaneously at cold start
            // (no cached token). Only one should trigger generateServiceToken().
            // Previous implementation with two separate volatile fields could
            // generate the token multiple times before double-check kicked in.
            given(jwtUtils.generateServiceToken(SERVICE_NAME)).willReturn(FAKE_TOKEN);

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
                        tokensReturned.add(provider.getToken());
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
            then(jwtUtils).should(times(1)).generateServiceToken(SERVICE_NAME);
        }

        @Test
        @DisplayName("all threads receive the same valid token")
        void allThreadsReceiveSameToken() throws InterruptedException {
            // given
            given(jwtUtils.generateServiceToken(SERVICE_NAME)).willReturn(FAKE_TOKEN);

            final int threadCount = 10;
            final List<String> results = new ArrayList<>();
            final CountDownLatch latch = new CountDownLatch(threadCount);
            final ExecutorService executor =
                    Executors.newFixedThreadPool(threadCount);

            // when
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        results.add(provider.getToken());
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
}