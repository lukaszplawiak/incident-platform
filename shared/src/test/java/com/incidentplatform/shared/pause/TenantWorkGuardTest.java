package com.incidentplatform.shared.pause;

import com.incidentplatform.shared.security.TenantAccess;
import com.incidentplatform.shared.security.TenantStatusProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@DisplayName("TenantWorkGuard")
class TenantWorkGuardTest {

    private final TenantStatusProvider provider = mock(TenantStatusProvider.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TenantWorkGuard guard = new TenantWorkGuard(provider, meterRegistry);

    private double held() {
        return meterRegistry.get("tenant.pause.guard.held").counter().count();
    }

    @Test
    @DisplayName("full access runs, uncounted")
    void fullRuns() {
        given(provider.accessOf("acme")).willReturn(TenantAccess.FULL);

        assertThat(guard.mayRun("acme")).isTrue();
        assertThat(held()).isZero();
    }

    @Test
    @DisplayName("either suspension holds the row back, counted; asked with accessOf, not the cache-only "
            + "knownAccessOf, which lets an uncached tenant through after a restart")
    void suspendedHeld() {
        given(provider.accessOf("acme")).willReturn(TenantAccess.READ_ONLY);
        given(provider.accessOf("globex")).willReturn(TenantAccess.NONE);

        assertThat(guard.mayRun("acme")).isFalse();
        assertThat(guard.mayRun("globex")).isFalse();
        assertThat(held()).isEqualTo(2);
        then(provider).should(never()).knownAccessOf(org.mockito.ArgumentMatchers.anyString());
    }

    /** A provider that counts its callers in flight, each held for {@code holdMillis}. */
    private static final class SlowProvider implements TenantStatusProvider {
        final java.util.Set<String> asked = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger maxInFlight = new java.util.concurrent.atomic.AtomicInteger();
        private final long holdMillis;

        SlowProvider(long holdMillis) {
            this.holdMillis = holdMillis;
        }

        @Override
        public TenantAccess accessOf(String tenantId) {
            asked.add(tenantId);
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(holdMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            if (tenantId.startsWith("broken")) {
                throw new IllegalStateException("lookup failed");
            }
            return TenantAccess.FULL;
        }
    }

    @Test
    @DisplayName("review: prefetch asks a batch's tenants in parallel, each once, at most "
            + "PREFETCH_PARALLELISM at a time, so the loop does not wait tenant after tenant")
    void prefetchInParallelBounded() {
        final SlowProvider slow = new SlowProvider(100);
        final List<String> tenants = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tenants.add("tenant-" + i);
            tenants.add("tenant-" + i);
        }
        try (TenantWorkGuard prefetching = new TenantWorkGuard(slow, meterRegistry)) {
            final long started = System.nanoTime();
            prefetching.prefetch(tenants);
            final long tookMillis = (System.nanoTime() - started) / 1_000_000;

            assertThat(slow.asked).hasSize(20);
            assertThat(slow.maxInFlight.get()).isGreaterThan(1).isLessThanOrEqualTo(
                    TenantWorkGuard.PREFETCH_PARALLELISM);
            assertThat(tookMillis).as("20 lookups of 100 ms, 8 at a time, not 2 s one after another")
                    .isLessThan(1_500);
        }
    }

    @Test
    @DisplayName("prefetch waits at most its limit, swallows a failed lookup, and skips a batch of one tenant")
    void prefetchBoundedAndQuiet() {
        final SlowProvider slow = new SlowProvider(2_000);
        try (TenantWorkGuard prefetching = new TenantWorkGuard(slow, meterRegistry,
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(), java.time.Duration.ofMillis(100))) {
            final long started = System.nanoTime();
            prefetching.prefetch(List.of("acme", "globex"));
            assertThat((System.nanoTime() - started) / 1_000_000).as("returned at its limit").isLessThan(1_000);
        }

        final SlowProvider failing = new SlowProvider(1);
        try (TenantWorkGuard prefetching = new TenantWorkGuard(failing, meterRegistry)) {
            org.assertj.core.api.Assertions.assertThatCode(
                    () -> prefetching.prefetch(List.of("broken-1", "broken-2"))).doesNotThrowAnyException();
            prefetching.prefetch(List.of("only-one", "only-one"));
            assertThat(failing.asked).as("a single tenant is left to mayRun").doesNotContain("only-one");
        }
    }
}
