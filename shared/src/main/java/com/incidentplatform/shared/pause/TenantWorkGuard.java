package com.incidentplatform.shared.pause;

import com.incidentplatform.shared.security.TenantAccess;
import com.incidentplatform.shared.security.TenantStatusProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The last check before a scheduler acts for a tenant (backlog #0-82, step 2b).
 * Its query already leaves out the tenants in {@link PausedTenants}, but that
 * table learns of a suspension only on the next {@link PausedTenantsSync}, up
 * to a sync interval plus the status cache's TTL after it (about 20 s): long
 * enough for a critical incident's five-minute escalation to fire. This
 * closes that gap per row with {@link TenantStatusProvider#accessOf}: the
 * status cache, asked again of auth-service when it is older than its TTL or
 * empty.
 *
 * <p>Not {@code knownAccessOf}, which never waits but answers FULL for a tenant
 * it has not cached yet: right after a restart every tenant, so a suspended
 * tenant's oldest rows went out in the first cycle, before the first sync
 * (found by the E2E test of step 2b). The schedulers run on virtual threads and
 * make blocking calls of their own (oncall-service, Slack, Gemini); one lookup
 * per tenant per TTL, bounded by the status client's timeouts (about 3 s while
 * auth-service is down, then the last known status), is affordable there.
 *
 * <p>A row it holds back is left exactly as it was: no attempt counted, no
 * state changed. The next sync pauses the tenant and the query stops returning
 * it.
 *
 * <h2>Prefetch (found in review)</h2>
 * Asked row by row, the lookups of a batch's distinct tenants ran one after
 * another: during an auth-service outage about 3 s each, so with 60 tenants in a
 * batch the last ones' escalations slipped a cycle or more (the processing
 * budget protects the ShedLock, not the latency). {@link #prefetch} asks for a
 * batch's tenants before the loop, a few at a time on virtual threads, so the
 * per-row check then reads the status cache; it waits at most {@code
 * PREFETCH_WAIT}, after which the loop goes on and a lookup still running is
 * joined by the provider's single flight. A caller starts its processing
 * budget before calling it, so the prefetch counts against the budget and not
 * against the margin below the ShedLock.
 *
 * <p>Accepted limit (found in review): a prefetched answer is good for one
 * status-cache TTL (10 s). A loop that runs longer, because each row makes slow
 * calls of its own, asks again row by row for the tenants it reaches after
 * that, one after another while auth-service is slow. Not prefetched again: by
 * then the pause sync has had a run, a suspended tenant is in the paused table
 * and the next batch's query leaves it out, so what the per-row check still
 * catches is the rare tenant suspended in the last seconds, and a second
 * prefetch would add a lookup per tenant per TTL for every long run.
 */
public class TenantWorkGuard implements AutoCloseable {

    /** Lookups a prefetch runs at once: enough to cover an outage's timeouts, few enough to spare auth-service. */
    static final int PREFETCH_PARALLELISM = 8;
    /** How long a prefetch waits for its lookups before the loop starts anyway. */
    static final Duration PREFETCH_WAIT = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(TenantWorkGuard.class);

    private final TenantStatusProvider provider;
    private final Counter heldBack;
    private final ExecutorService prefetchExecutor;
    private final Duration prefetchWait;

    public TenantWorkGuard(TenantStatusProvider provider, MeterRegistry meterRegistry) {
        this(provider, meterRegistry, Executors.newVirtualThreadPerTaskExecutor(), PREFETCH_WAIT);
    }

    /** Test seam: the executor of prefetch lookups and how long a prefetch waits. */
    TenantWorkGuard(TenantStatusProvider provider, MeterRegistry meterRegistry, ExecutorService prefetchExecutor,
                    Duration prefetchWait) {
        this.provider = provider;
        this.prefetchExecutor = prefetchExecutor;
        this.prefetchWait = prefetchWait;
        this.heldBack = Counter.builder("tenant.pause.guard.held")
                .description("Rows a scheduler left alone because their tenant is suspended and not yet in its "
                        + "paused table (backlog #0-82)")
                .register(meterRegistry);
    }

    /** Whether the scheduler may act for this tenant now; {@code false} counts and logs the row held back. */
    public boolean mayRun(String tenantId) {
        final TenantAccess access = provider.accessOf(tenantId);
        if (access == TenantAccess.FULL) {
            return true;
        }
        heldBack.increment();
        log.info("Background work held back, tenant suspended: tenant={}, access={}", tenantId, access);
        return false;
    }

    /**
     * Looks up the status of a batch's distinct tenants before the scheduler's
     * loop, at most {@link #PREFETCH_PARALLELISM} at a time, and waits at most
     * {@code PREFETCH_WAIT} for them, so that {@link #mayRun} then reads the
     * cache instead of waiting tenant after tenant. Never fails the run: a lookup
     * that fails or is still running only leaves {@link #mayRun} to ask itself.
     */
    public void prefetch(Collection<String> tenantIds) {
        final Set<String> distinct = new LinkedHashSet<>(tenantIds);
        if (distinct.size() < 2) {
            return;
        }
        final Semaphore permits = new Semaphore(PREFETCH_PARALLELISM);
        final CompletableFuture<?>[] lookups = distinct.stream()
                .map(tenantId -> CompletableFuture.runAsync(() -> lookUp(tenantId, permits), prefetchExecutor))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(lookups).get(prefetchWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Tenant status prefetch still running after {}, the batch goes on: tenants={}",
                    prefetchWait, distinct.size());
        } catch (ExecutionException e) {
            // lookUp catches every RuntimeException; only an Error reaches here.
            log.error("Tenant status prefetch failed: error={}", e.getCause().getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void lookUp(String tenantId, Semaphore permits) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            provider.accessOf(tenantId);
        } catch (RuntimeException e) {
            // The per-row check asks again and handles it; here it only cost a warm cache.
            log.debug("Tenant status prefetch failed for a tenant: tenant={}, error={}", tenantId,
                    e.getClass().getSimpleName());
        } finally {
            permits.release();
        }
    }

    /** Stops the prefetch threads with the context (a {@code @Bean}'s inferred destroy method). */
    @Override
    public void close() {
        prefetchExecutor.shutdownNow();
    }
}
