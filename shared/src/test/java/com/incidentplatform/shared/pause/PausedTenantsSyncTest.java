package com.incidentplatform.shared.pause;

import com.incidentplatform.shared.security.TenantAccess;
import com.incidentplatform.shared.security.TenantAccessState;
import com.incidentplatform.shared.security.TenantStatusProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * What a pause sync decides (backlog #0-82, step 2b); the SQL and the
 * transaction on a real database are {@link PausedTenantsTest}'s.
 */
@DisplayName("PausedTenantsSync")
class PausedTenantsSyncTest {

    private static final Instant PAUSED_AT = Instant.parse("2026-10-06T09:00:00Z");
    /** When auth-service says the tenants in these tests were suspended. */
    private static final Instant SUSPENDED_AT = Instant.parse("2026-10-06T08:59:30Z");

    private static Optional<TenantAccessState> state(TenantAccess access) {
        return Optional.of(new TenantAccessState(access, access == TenantAccess.FULL ? null : SUSPENDED_AT));
    }

    private PausedTenants pausedTenants;
    private PausableWork work;
    private TenantStatusProvider provider;
    private LockProvider lockProvider;
    private SimpleMeterRegistry meterRegistry;
    private MutableClock clock;
    private PausedTenantsSync sync;

    @BeforeEach
    void setUp() {
        pausedTenants = mock(PausedTenants.class);
        given(pausedTenants.table()).willReturn("test_paused_tenants");
        work = mock(PausableWork.class);
        provider = mock(TenantStatusProvider.class);
        lockProvider = mock(LockProvider.class);
        meterRegistry = new SimpleMeterRegistry();
        clock = new MutableClock(Instant.parse("2026-10-06T10:00:00Z"));
        sync = sync(Duration.ofSeconds(90));
    }

    private PausedTenantsSync sync(Duration budget) {
        final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        return new PausedTenantsSync(pausedTenants, work, provider, transactionManager, lockProvider, meterRegistry,
                new PausedTenantsProperties("test_paused_tenants", budget), clock);
    }

    private void paused(String... tenants) {
        given(pausedTenants.all()).willReturn(java.util.Arrays.stream(tenants)
                .map(t -> new PausedTenants.Paused(t, TenantAccess.NONE, PAUSED_AT)).toList());
    }

    private double changes(String change) {
        return meterRegistry.get("tenant.pause.changes").tag("change", change).counter().count();
    }

    private double failures(String kind) {
        return meterRegistry.get("tenant.pause.sync.failures").tag("kind", kind).counter().count();
    }

    @Test
    @DisplayName("a suspended tenant with waiting work is paused in its mode, counted")
    void pausesSuspended() {
        paused();
        given(work.tenantsWithPendingWork()).willReturn(List.of("acme", "globex"));
        given(provider.confirmedStateOf("acme")).willReturn(state(TenantAccess.READ_ONLY));
        given(provider.confirmedStateOf("globex")).willReturn(state(TenantAccess.FULL));
        given(pausedTenants.pause("acme", TenantAccess.READ_ONLY, SUSPENDED_AT)).willReturn(PausedTenants.Change.PAUSED);

        final PausedTenantsSync.RunResult result = sync.syncNow();

        assertThat(result.paused()).isEqualTo(1);
        then(pausedTenants).should(never()).pause(org.mockito.ArgumentMatchers.eq("globex"), any(), any());
        then(pausedTenants).should(never()).lockForResume(anyString());
        assertThat(changes("paused")).isEqualTo(1);
    }

    @Test
    @DisplayName("a change of mode is counted as such; an unchanged pause is not counted at all")
    void modeChangeAndUnchanged() {
        paused("acme", "globex");
        given(work.tenantsWithPendingWork()).willReturn(List.of());
        given(provider.confirmedStateOf("acme")).willReturn(state(TenantAccess.NONE));
        given(provider.confirmedStateOf("globex")).willReturn(state(TenantAccess.NONE));
        given(pausedTenants.pause("acme", TenantAccess.NONE, SUSPENDED_AT)).willReturn(PausedTenants.Change.MODE_CHANGED);
        given(pausedTenants.pause("globex", TenantAccess.NONE, SUSPENDED_AT)).willReturn(PausedTenants.Change.UNCHANGED);

        final PausedTenantsSync.RunResult result = sync.syncNow();

        assertThat(result.modeChanged()).isEqualTo(1);
        assertThat(result.paused()).isZero();
        assertThat(changes("mode_changed")).isEqualTo(1);
        assertThat(changes("paused")).isZero();
    }

    @Test
    @DisplayName("a resumed tenant's work is released: the hook runs with the pause's start (the suspension's "
            + "own time), then the row goes")
    void resumes() {
        paused("acme");
        given(work.tenantsWithPendingWork()).willReturn(List.of("acme"));
        given(provider.confirmedStateOf("acme")).willReturn(state(TenantAccess.FULL));
        given(pausedTenants.lockForResume("acme")).willReturn(Optional.of(PAUSED_AT));

        final PausedTenantsSync.RunResult result = sync.syncNow();

        assertThat(result.resumed()).isEqualTo(1);
        final InOrder order = inOrder(pausedTenants, work);
        order.verify(pausedTenants).lockForResume("acme");
        order.verify(work).onResume("acme", PAUSED_AT);
        order.verify(pausedTenants).delete("acme");
        assertThat(changes("resumed")).isEqualTo(1);
    }

    @Test
    @DisplayName("a resumption that finds the row gone (another run took it) neither runs the hook nor counts")
    void resumeOfRowAlreadyGone() {
        paused("acme");
        given(work.tenantsWithPendingWork()).willReturn(List.of());
        given(provider.confirmedStateOf("acme")).willReturn(state(TenantAccess.FULL));
        given(pausedTenants.lockForResume("acme")).willReturn(Optional.empty());

        assertThat(sync.syncNow().resumed()).isZero();
        then(work).should(never()).onResume(anyString(), any());
        then(pausedTenants).should(never()).delete(anyString());
        assertThat(changes("resumed")).isZero();
    }

    @Test
    @DisplayName("no answer from auth-service ever: a paused tenant stays paused, an unpaused one is not paused")
    void unconfirmedChangesNothing() {
        paused("acme");
        given(work.tenantsWithPendingWork()).willReturn(List.of("globex"));
        given(provider.confirmedStateOf(anyString())).willReturn(Optional.empty());

        final PausedTenantsSync.RunResult result = sync.syncNow();

        assertThat(result.unconfirmed()).isEqualTo(2);
        then(pausedTenants).should(never()).pause(anyString(), any(), any());
        then(pausedTenants).should(never()).lockForResume(anyString());
        then(pausedTenants).should(never()).delete(anyString());
        assertThat(meterRegistry.get("tenant.pause.unconfirmed").counter().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("paused tenants and tenants with work are asked about together, in id order, each once")
    void allCandidatesInOrderAndDistinct() {
        paused("zeta");
        given(work.tenantsWithPendingWork()).willReturn(List.of("acme", "zeta", "acme"));
        given(provider.confirmedStateOf(anyString())).willReturn(Optional.empty());

        sync.syncNow();

        final ArgumentCaptor<String> asked = ArgumentCaptor.forClass(String.class);
        then(provider).should(org.mockito.Mockito.times(2)).confirmedStateOf(asked.capture());
        assertThat(asked.getAllValues()).containsExactly("acme", "zeta");
        assertThat(meterRegistry.get("tenant.pause.sync.runs").counter().count()).as("a completed run").isEqualTo(1);
    }

    @Test
    @DisplayName("a failure for one tenant is counted and the others are still synced")
    void tenantFailureIsolated() {
        paused();
        given(work.tenantsWithPendingWork()).willReturn(List.of("acme", "globex"));
        given(provider.confirmedStateOf("acme")).willThrow(new IllegalStateException("defect"));
        given(provider.confirmedStateOf("globex")).willReturn(state(TenantAccess.NONE));
        given(pausedTenants.pause("globex", TenantAccess.NONE, SUSPENDED_AT)).willReturn(PausedTenants.Change.PAUSED);

        final PausedTenantsSync.RunResult result = sync.syncNow();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.paused()).isEqualTo(1);
        assertThat(failures("tenant")).isEqualTo(1);
    }

    @Test
    @DisplayName("a run that cannot read its candidates is counted and asks nothing")
    void runFailure() {
        given(pausedTenants.all()).willThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));

        assertThat(sync.syncNow().failed()).isEqualTo(1);
        then(provider).shouldHaveNoInteractions();
        assertThat(failures("run")).isEqualTo(1);
        assertThat(meterRegistry.get("tenant.pause.sync.runs").counter().count())
                .as("not a completed run (registered at zero for TenantPauseSyncStalled)").isZero();
    }

    @Test
    @DisplayName("past the processing budget the rest waits for the next run, but one tenant is always done")
    void budget() {
        sync = sync(Duration.ofSeconds(5));
        paused();
        given(work.tenantsWithPendingWork()).willReturn(List.of("acme", "globex", "initech"));
        given(provider.confirmedStateOf(anyString())).willAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(6));
            return Optional.empty();
        });

        final PausedTenantsSync.RunResult result = sync.syncNow();

        assertThat(result.unconfirmed()).isEqualTo(1);
        assertThat(result.leftForNext()).isEqualTo(2);
    }

    @Test
    @DisplayName("the scheduled run takes the lock named after the table, and does nothing when another holds it")
    void lock() {
        given(lockProvider.lock(any())).willReturn(Optional.empty());

        sync.sync();

        final ArgumentCaptor<LockConfiguration> lock = ArgumentCaptor.forClass(LockConfiguration.class);
        then(lockProvider).should().lock(lock.capture());
        assertThat(lock.getValue().getName()).isEqualTo("paused-tenants-sync-test_paused_tenants")
                .isEqualTo(sync.lockName());
        assertThat(lock.getValue().getLockAtMostFor()).isEqualTo(PausedTenantsSync.LOCK_AT_MOST_FOR);
        then(pausedTenants).should(never()).all();
    }

    @Test
    @DisplayName("review: the pause starts at the suspension's own time from auth-service, not when the sync saw it")
    void pauseTakesSuspensionTime() {
        paused();
        given(work.tenantsWithPendingWork()).willReturn(List.of("acme"));
        given(provider.confirmedStateOf("acme")).willReturn(state(TenantAccess.NONE));
        given(pausedTenants.pause("acme", TenantAccess.NONE, SUSPENDED_AT)).willReturn(PausedTenants.Change.PAUSED);

        assertThat(sync.syncNow().paused()).isEqualTo(1);
        then(pausedTenants).should().pause("acme", TenantAccess.NONE, SUSPENDED_AT);
    }

    @Test
    @DisplayName("review: a run cut short by its budget is continued after the last tenant it reached, so the "
            + "tail is not starved; a full run starts at the head again")
    void rotationAfterBudget() {
        sync = sync(Duration.ofSeconds(5));
        paused();
        given(work.tenantsWithPendingWork()).willReturn(List.of("delta", "alpha", "charlie", "bravo"));
        final List<String> asked = new java.util.ArrayList<>();
        given(provider.confirmedStateOf(anyString())).willAnswer(invocation -> {
            asked.add(invocation.getArgument(0));
            clock.advance(Duration.ofSeconds(3));
            return Optional.empty();
        });

        sync.syncNow();
        assertThat(asked).as("sorted, cut after two").containsExactly("alpha", "bravo");

        asked.clear();
        sync.syncNow();
        assertThat(asked).as("continues after bravo").containsExactly("charlie", "delta");

        asked.clear();
        sync = sync(Duration.ofSeconds(90));
        sync.syncNow();
        assertThat(asked).as("a fresh instance, a whole run").containsExactly("alpha", "bravo", "charlie", "delta");
    }

    @Test
    @DisplayName("review: paused tenants rotate with the rest, so enough of them cannot use up every budget and "
            + "leave a new suspension unrecorded")
    void pausedTenantsRotateToo() {
        sync = sync(Duration.ofSeconds(5));
        paused("alpha", "bravo", "charlie");
        given(work.tenantsWithPendingWork()).willReturn(List.of("delta"));
        final List<String> asked = new java.util.ArrayList<>();
        given(provider.confirmedStateOf(anyString())).willAnswer(invocation -> {
            asked.add(invocation.getArgument(0));
            clock.advance(Duration.ofSeconds(3));
            return Optional.empty();
        });

        sync.syncNow();
        sync.syncNow();

        assertThat(asked).as("two runs of two, the not-yet-paused delta reached in the second")
                .containsExactly("alpha", "bravo", "charlie", "delta");
    }

    @Test
    @DisplayName("the paused gauge reads the table, cached for a few seconds, NaN when it cannot")
    void gauge() {
        given(pausedTenants.count()).willReturn(3L, 4L);
        final var gauge = meterRegistry.get("tenant.pause.paused").gauge();

        assertThat(gauge.value()).isEqualTo(3);
        assertThat(gauge.value()).as("cached").isEqualTo(3);
        clock.advance(PausedTenantsSync.COUNT_CACHE);
        assertThat(gauge.value()).isEqualTo(4);

        given(pausedTenants.count()).willThrow(new IllegalStateException("down"));
        clock.advance(PausedTenantsSync.COUNT_CACHE);
        assertThat(gauge.value()).isNaN();
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
