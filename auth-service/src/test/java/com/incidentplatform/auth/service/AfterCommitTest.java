package com.incidentplatform.auth.service;

import com.incidentplatform.shared.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AfterCommit (backlog #0-89)")
class AfterCommitTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<Runnable> handedOver = new ArrayList<>();
    private final AfterCommit afterCommit = new AfterCommit(handedOver::add, meters);

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("inside a transaction the work is handed to the executor only at commit, never run on the caller")
    void handsOverAtCommit() {
        final List<String> done = new ArrayList<>();
        TransactionSynchronizationManager.initSynchronization();

        afterCommit.run(() -> done.add("audit"));
        assertThat(handedOver).as("nothing before the commit").isEmpty();

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(handedOver).hasSize(1);
        assertThat(done).as("the executor runs it, not the committing thread").isEmpty();

        handedOver.get(0).run();
        assertThat(done).containsExactly("audit");
    }

    @Test
    @DisplayName("without a transaction the work goes to the executor at once")
    void handsOverWithoutTransaction() {
        afterCommit.run(() -> { });
        assertThat(handedOver).hasSize(1);
    }

    @Test
    @DisplayName("a full executor drops the event, counted, without failing the committed request")
    void fullExecutorDropsAndCounts() {
        final Executor full = work -> {
            throw new TaskRejectedException("queue full");
        };

        new AfterCommit(full, meters).run(() -> { });

        assertThat(meters.counter("auth.audit.after_commit.rejected", "reason", "queue_full").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a publish that throws is counted as failed and does not escape (review)")
    void failingPublishCounted() {
        final AfterCommit direct = new AfterCommit(Runnable::run, meters);

        direct.run(() -> {
            throw new IllegalStateException("producer closed");
        });

        assertThat(meters.counter("auth.audit.after_commit.rejected", "reason", "failed").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("the real pool: the tenant reaches the task on a daemon thread, is cleared after it, and a "
            + "stopped pool refuses work, counted (review)")
    void realPool() throws Exception {
        final AfterCommit real = new AfterCommit(meters);
        try {
            real.start();
            assertThat(real.isRunning()).isTrue();
            final AtomicReference<String> tenantInTask = new AtomicReference<>();
            final AtomicBoolean daemon = new AtomicBoolean();
            final CountDownLatch first = new CountDownLatch(1);
            TenantContext.set("tenant-a");
            try {
                real.run(() -> {
                    tenantInTask.set(TenantContext.getOrNull());
                    daemon.set(Thread.currentThread().isDaemon());
                    first.countDown();
                });
            } finally {
                TenantContext.clear();
            }
            assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(tenantInTask.get()).isEqualTo("tenant-a");
            assertThat(daemon.get()).as("never keeps the JVM alive").isTrue();

            final AtomicReference<String> leftOver = new AtomicReference<>("unset");
            final CountDownLatch second = new CountDownLatch(1);
            real.run(() -> {
                leftOver.set(TenantContext.getOrNull());
                second.countDown();
            });
            assertThat(second.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(leftOver.get()).as("no tenant left on the pool thread").isNull();
        } finally {
            real.stop();
        }
        assertThat(real.isRunning()).isFalse();

        real.run(() -> { });
        assertThat(meters.counter("auth.audit.after_commit.rejected", "reason", "queue_full").count())
                .as("a stopped pool refuses, counted").isEqualTo(1.0);
    }

    @Test
    @DisplayName("stops after the web server and before the Kafka producer factory (phase Integer.MIN_VALUE)")
    void lifecyclePhase() {
        assertThat(afterCommit.getPhase())
                .isLessThan(SmartLifecycle.DEFAULT_PHASE - 2048)
                .isGreaterThan(Integer.MIN_VALUE);
    }
}
