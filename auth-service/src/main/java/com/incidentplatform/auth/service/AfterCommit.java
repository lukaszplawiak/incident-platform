package com.incidentplatform.auth.service;

import com.incidentplatform.shared.security.TenantAwareTaskDecorator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs audit publishing once the current transaction has committed, on the
 * audit executor (backlog #0-89, found in review).
 *
 * <h2>Why after commit</h2>
 * The API key creations hold the creator's row lock until commit, and the
 * audit publish can block on Kafka (metadata wait, retries) for seconds or
 * more; after commit the lock is gone, and the event no longer records a key
 * whose creation was then rolled back.
 *
 * <h2>Why on an executor</h2>
 * Measured in review ({@code AuthRepositoryIntegrationTest}): when
 * {@code afterCommit} runs, the request's pooled connection is still checked
 * out (Spring releases it after the synchronizations). Publishing there would
 * hold one of the few connections for as long as Kafka stalls, so a handful
 * of creations could starve the service. On the executor the request returns
 * and frees its connection at once.
 *
 * <p>Still fire-and-forget, as every audit event until #0-84's outbox: a full
 * queue, a publish that throws, a Kafka outage or a shutdown before the queue
 * drains loses the event. The first two are logged and counted in
 * {@code auth.audit.after_commit.rejected} (tag {@code reason}: {@code queue_full},
 * {@code failed}), alerted by {@code AuditEventsDropped}; a send that fails later
 * inside the Kafka client is not seen here. Without an active transaction the
 * work is handed over at once.
 *
 * <h2>Its own executor, not a bean</h2>
 * Two threads and a queue of 1,000 events, bounded on purpose: a Kafka stall
 * costs events, not memory. The tenant goes along
 * ({@link TenantAwareTaskDecorator}, as CLAUDE.md requires for any hand-off).
 * Kept private (found in review): an {@code Executor} bean would make Spring
 * Boot back off its own {@code applicationTaskExecutor}, so any later
 * {@code @Async} would silently run on this small pool.
 *
 * <h2>Shutdown order (found in review)</h2>
 * A {@link SmartLifecycle} in phase {@value #PHASE}: stopped after the web
 * server (its phases are near {@code Integer.MAX_VALUE}, so no request adds
 * events any more) and before the Kafka producer factory (phase
 * {@code Integer.MIN_VALUE}, whose {@code stop()} closes the producer), so the
 * queue drains while the producer still works, waiting up to 10 s. Daemon
 * threads: a drain stuck on a dead Kafka never keeps the JVM alive.
 */
@Component
public class AfterCommit implements SmartLifecycle, DisposableBean {

    /** Between the web server's lifecycle phases and the Kafka producer factory's. */
    static final int PHASE = 0;

    static final int THREADS = 2;
    static final int QUEUE_CAPACITY = 1000;

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private final Executor executor;
    private final ThreadPoolTaskExecutor ownExecutor;
    private final Counter rejected;
    private final Counter failed;
    private volatile boolean running;

    @Autowired
    public AfterCommit(MeterRegistry meterRegistry) {
        this(newAuditExecutor(), meterRegistry);
    }

    /** For tests: runs the work on {@code executor}. */
    AfterCommit(Executor executor, MeterRegistry meterRegistry) {
        this.executor = executor;
        this.ownExecutor = executor instanceof ThreadPoolTaskExecutor pool ? pool : null;
        this.rejected = rejectedCounter("queue_full", meterRegistry);
        this.failed = rejectedCounter("failed", meterRegistry);
    }

    private static Counter rejectedCounter(String reason, MeterRegistry meterRegistry) {
        return Counter.builder("auth.audit.after_commit.rejected")
                .description("Audit events lost before reaching the Kafka client: the audit queue was full, "
                        + "or the publish threw (backlog #0-89)")
                .tag("reason", reason)
                .register(meterRegistry);
    }

    private static ThreadPoolTaskExecutor newAuditExecutor() {
        final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(THREADS);
        executor.setMaxPoolSize(THREADS);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("audit-after-commit-");
        executor.setDaemon(true);
        executor.setTaskDecorator(new TenantAwareTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Override
    public void start() {
        running = true;
    }

    /** Drains the queue (up to 10 s) before the Kafka producer is closed; see the class Javadoc. */
    @Override
    public void stop() {
        running = false;
        if (ownExecutor != null) {
            ownExecutor.shutdown();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /** In case the context closes without stopping lifecycles; a second shutdown does nothing. */
    @Override
    public void destroy() {
        stop();
    }

    public void run(Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            hand(work);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                hand(work);
            }
        });
    }

    private void hand(Runnable work) {
        try {
            executor.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException publishFailed) {
                    failed.increment();
                    log.error("Audit event dropped: publishing it failed", publishFailed);
                }
            });
        } catch (RejectedExecutionException full) { // TaskRejectedException included
            rejected.increment();
            log.error("Audit event dropped: the audit queue is full (Kafka slow or down?) or shut down", full);
        }
    }
}
