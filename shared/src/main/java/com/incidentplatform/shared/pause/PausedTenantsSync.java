package com.incidentplatform.shared.pause;

import com.incidentplatform.shared.security.TenantAccess;
import com.incidentplatform.shared.security.TenantAccessState;
import com.incidentplatform.shared.security.TenantStatusProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Keeps a service's {@link PausedTenants} in step with auth-service (backlog
 * #0-82, step 2b): a suspended tenant's background work is held back, and
 * resumed when the tenant is.
 *
 * <h2>What it asks about</h2>
 * The tenants this service has work waiting for ({@link PausableWork}) and the
 * ones it holds paused, together in tenant id order, each run going on after
 * the last tenant the previous run reached when its budget ran out (found in
 * review: first the paused ones were always asked first, and only the rest
 * rotated, so enough paused tenants during an auth-service outage, about 3 s a
 * lookup, used up every budget and no new suspension was ever recorded). Not
 * every tenant: there is no tenant-less way to ask auth-service for a
 * list (its tokens name one tenant), and a tenant with nothing waiting needs no
 * pause. Its next row makes it a candidate within one sync.
 *
 * <h2>Only auth-service's own answer changes anything</h2>
 * {@link TenantStatusProvider#confirmedStateOf}: a tenant is paused or resumed
 * on what auth-service said, now or last before an outage, never on the FULL
 * the provider gives a tenant it could not ask about. So an outage of
 * auth-service right after this service restarts (an empty status cache) does
 * not resume every paused tenant, and a tenant never answered for is not paused
 * (fail-open, as the status filter, decided in step 2a). Such a tenant is
 * counted ({@code tenant.pause.unconfirmed}); the provider's own alerts
 * ({@code TenantStatusLookupFailing}, {@code TenantStatusLookupRejected}) say
 * why.
 *
 * <h2>Resumption</h2>
 * One transaction: the tenant's row locked, {@link PausableWork#onResume}
 * (escalation-service moves its timers on by the pause's length), the row
 * deleted. {@code paused_at} is the suspension's own time, {@code suspended_at}
 * from auth-service ({@link TenantAccessState#since}), not when this sync saw
 * it: measured from the sync, a late run (its budget used up, a failing
 * database) moved a timer by less than the tenant was held, and the task
 * escalated the moment it was resumed (found in review; a fixed allowance for
 * the sync's delay was tried first and could not bound it). A scheduler's query sees the tenant's work again only once all of
 * it has committed.
 *
 * <p>One instance at a time (ShedLock, a lock named after the table); every
 * {@code tenant-pause.sync-interval-ms} (10 s). A run spends at most
 * {@code processing-budget} on lookups. A failure for one tenant is counted
 * and the run goes on; a failure of the run itself (the database) is counted
 * too. Both feed the {@code TenantPauseSyncFailing} alert.
 */
public class PausedTenantsSync {

    private static final Logger log = LoggerFactory.getLogger(PausedTenantsSync.class);

    /** The sync's lock name is this plus the table (see {@link PausedTenants#MAX_TABLE_NAME_LENGTH}). */
    static final String LOCK_PREFIX = "paused-tenants-sync-";
    static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(2);
    /** Room left for the lookup in flight when the budget runs out. */
    static final Duration LOCK_MARGIN = Duration.ofSeconds(15);
    static final Duration COUNT_CACHE = Duration.ofSeconds(5);

    /** What one run did. */
    public record RunResult(int paused, int modeChanged, int resumed, int unconfirmed, int failed, int leftForNext) {
    }

    private final PausedTenants pausedTenants;
    private final PausableWork work;
    private final TenantStatusProvider provider;
    private final TransactionTemplate transaction;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final Duration processingBudget;
    private final Clock clock;
    private final String lockName;

    private final Counter pausedCounter;
    private final Counter modeChangedCounter;
    private final Counter resumedCounter;
    private final Counter unconfirmedCounter;
    private final Counter tenantFailures;
    private final Counter runFailures;
    private final Counter runs;

    private record CachedCount(double value, Instant readAt) {
    }

    private volatile CachedCount cachedCount;

    /** Where the next run starts among the not-yet-paused candidates; see {@link #rotated}. */
    private volatile String resumeAfter;

    public PausedTenantsSync(PausedTenants pausedTenants,
                             PausableWork work,
                             TenantStatusProvider provider,
                             PlatformTransactionManager transactionManager,
                             LockProvider lockProvider,
                             MeterRegistry meterRegistry,
                             PausedTenantsProperties properties,
                             Clock clock) {
        this.pausedTenants = pausedTenants;
        this.work = work;
        this.provider = provider;
        this.transaction = new TransactionTemplate(transactionManager);
        this.lockingTaskExecutor = new DefaultLockingTaskExecutor(lockProvider);
        this.processingBudget = properties.processingBudget();
        this.clock = clock;
        this.lockName = LOCK_PREFIX + pausedTenants.table();
        this.pausedCounter = change(meterRegistry, "paused");
        this.modeChangedCounter = change(meterRegistry, "mode_changed");
        this.resumedCounter = change(meterRegistry, "resumed");
        this.unconfirmedCounter = Counter.builder("tenant.pause.unconfirmed")
                .description("Tenants a pause sync left as they were, auth-service never having answered for "
                        + "them (backlog #0-82)")
                .register(meterRegistry);
        this.tenantFailures = failure(meterRegistry, "tenant");
        this.runFailures = failure(meterRegistry, "run");
        // Registered at zero on every replica: the TenantPauseSyncStalled alert
        // fires when no replica completes a run (found in review: the failure
        // counter says nothing when the sync does not run at all).
        this.runs = Counter.builder("tenant.pause.sync.runs")
                .description("Pause syncs completed, on whichever replica held the lock (backlog #0-82)")
                .register(meterRegistry);
        // Read from the table when Prometheus scrapes, on every replica, as the
        // audit outbox's backlog gauges are: a sync that stopped shows as a
        // count that no longer follows the suspensions, not a frozen value.
        Gauge.builder("tenant.pause.paused", this, PausedTenantsSync::pausedCount)
                .description("Tenants whose background work this service holds back (backlog #0-82)")
                .register(meterRegistry);
    }

    private static Counter change(MeterRegistry registry, String change) {
        return Counter.builder("tenant.pause.changes").tag("change", change)
                .description("Pauses of a suspended tenant's background work begun, changed and ended "
                        + "(backlog #0-82)")
                .register(registry);
    }

    private static Counter failure(MeterRegistry registry, String kind) {
        return Counter.builder("tenant.pause.sync.failures").tag("kind", kind)
                .description("Pause syncs that failed, for one tenant or as a whole (backlog #0-82)")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${tenant-pause.sync-interval-ms:10000}",
            initialDelayString = "${tenant-pause.sync-interval-ms:10000}")
    public void sync() {
        lockingTaskExecutor.executeWithLock((Runnable) this::syncNow, new LockConfiguration(
                clock.instant(), lockName, LOCK_AT_MOST_FOR, Duration.ZERO));
    }

    /** One run, without the lock (the scheduled {@link #sync} takes it); for tests. */
    RunResult syncNow() {
        final Map<String, PausedTenants.Paused> paused;
        final List<String> candidates = new ArrayList<>();
        try {
            paused = pausedTenants.all().stream()
                    .collect(Collectors.toMap(PausedTenants.Paused::tenantId, Function.identity(),
                            (a, b) -> a, LinkedHashMap::new));
            candidates.addAll(rotated(paused.keySet(), work.tenantsWithPendingWork()));
        } catch (RuntimeException e) {
            runFailures.increment();
            log.error("Pause sync could not read its candidates: table={}, error={}",
                    pausedTenants.table(), e.getClass().getSimpleName(), e);
            return new RunResult(0, 0, 0, 0, 1, 0);
        }

        final Instant deadline = clock.instant().plus(processingBudget);
        int done = 0;
        int pausedNow = 0;
        int modeChanged = 0;
        int resumed = 0;
        int unconfirmed = 0;
        int failed = 0;
        for (final String tenantId : candidates) {
            if (done > 0 && clock.instant().isAfter(deadline)) {
                log.warn("Pause sync budget of {} used up: {} of {} tenants left for the next run",
                        processingBudget, candidates.size() - done, candidates.size());
                break;
            }
            done++;
            resumeAfter = tenantId;
            try {
                final Optional<TenantAccessState> state = provider.confirmedStateOf(tenantId);
                if (state.isEmpty()) {
                    unconfirmed++;
                    unconfirmedCounter.increment();
                    continue;
                }
                final TenantAccess access = state.get().access();
                if (access == TenantAccess.FULL) {
                    if (paused.containsKey(tenantId) && resume(tenantId)) {
                        resumed++;
                    }
                    continue;
                }
                switch (pausedTenants.pause(tenantId, access, state.get().since())) {
                    case PAUSED -> {
                        pausedNow++;
                        pausedCounter.increment();
                        log.info("Background work of a suspended tenant paused: tenant={}, access={}, since={}, "
                                + "table={}", tenantId, access, state.get().since(), pausedTenants.table());
                    }
                    case MODE_CHANGED -> {
                        modeChanged++;
                        modeChangedCounter.increment();
                        log.info("Suspended tenant's mode changed, its work stays paused: tenant={}, access={}",
                                tenantId, access);
                    }
                    case UNCHANGED -> {
                    }
                }
            } catch (RuntimeException e) {
                failed++;
                tenantFailures.increment();
                log.error("Pause sync failed for a tenant, trying again next run: tenant={}, error={}",
                        tenantId, e.getClass().getSimpleName(), e);
            }
        }
        if (done == candidates.size()) {
            resumeAfter = null;
        }
        runs.increment();
        return new RunResult(pausedNow, modeChanged, resumed, unconfirmed, failed, candidates.size() - done);
    }

    /**
     * Every candidate, paused or with waiting work, once, in tenant id order,
     * starting after the last one the previous run reached when its budget ran
     * out. The cursor is this JVM's; another replica taking the lock starts at
     * the head, which delays the rest by a run, never more.
     */
    private List<String> rotated(Collection<String> paused, Collection<String> withWork) {
        final TreeSet<String> sorted = new TreeSet<>(paused);
        sorted.addAll(withWork);
        final String cursor = resumeAfter;
        if (cursor == null) {
            return new ArrayList<>(sorted);
        }
        final List<String> rotated = new ArrayList<>(sorted.tailSet(cursor, false));
        rotated.addAll(sorted.headSet(cursor, true));
        return rotated;
    }

    private boolean resume(String tenantId) {
        final Optional<Instant> pausedAt = transaction.execute(status -> {
            final Optional<Instant> since = pausedTenants.lockForResume(tenantId);
            if (since.isPresent()) {
                work.onResume(tenantId, since.get());
                pausedTenants.delete(tenantId);
            }
            return since;
        });
        if (pausedAt == null || pausedAt.isEmpty()) {
            return false;
        }
        resumedCounter.increment();
        log.info("Background work of a resumed tenant released: tenant={}, pausedFor={}, table={}",
                tenantId, Duration.between(pausedAt.get(), clock.instant()), pausedTenants.table());
        return true;
    }

    private double pausedCount() {
        final CachedCount cached = cachedCount;
        final Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.readAt().plus(COUNT_CACHE))) {
            return cached.value();
        }
        double value;
        try {
            value = pausedTenants.count();
        } catch (RuntimeException e) {
            value = Double.NaN;
        }
        cachedCount = new CachedCount(value, now);
        return value;
    }

    String lockName() {
        return lockName;
    }
}
