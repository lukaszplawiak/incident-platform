package com.incidentplatform.shared.audit;

import com.incidentplatform.shared.security.InvalidTenantIdException;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.TenantIds;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.apache.kafka.common.InvalidRecordException;
import org.apache.kafka.common.errors.RecordBatchTooLargeException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends a service's committed audit events from its outbox to Kafka
 * (backlog #0-84), modelled on {@code IncidentEventOutboxScheduler} (#36).
 *
 * <ul>
 *   <li>One instance at a time: a ShedLock lock named after the table, as
 *       every service shares the {@code shedlock} table and each relays its
 *       own outbox. A poll first asks whether any row is due, without a lock,
 *       and takes the lock only when one is (found in review: the lock is a
 *       write to that shared table, and it was taken on every poll).</li>
 *   <li>A batch is handed to the producer first and its acknowledgements
 *       awaited afterwards, all within one {@code send-timeout}: one round
 *       trip per batch, not per event (found in review: waiting per event
 *       capped a service at a few dozen events a second). A run takes up to
 *       {@code max-batches-per-run} full batches.</li>
 *   <li>A batch's acknowledged rows are marked sent in one statement. A failed
 *       row is retried with backoff (5 s doubling, at most 5 min, due by the
 *       database's clock) and never given up: an audit event is kept until it
 *       goes, and while it waits the {@code AuditOutboxBacklog} alert fires. A
 *       row Kafka refuses for itself (larger than a record may be) keeps the
 *       alert firing until someone looks, but holds no other row up: the
 *       publisher refuses payloads over 256 KiB, so it should not happen.</li>
 *   <li>After Kafka itself failed (a timeout, a broker error) the relay pauses
 *       (the same backoff, by consecutive failed runs), so an outage costs one
 *       timeout per pause rather than one per poll and new row (found in
 *       review: the scheduling thread is shared with the service's other
 *       jobs).</li>
 *   <li>At least once: a crash, or a failure to mark the row, between the
 *       acknowledgement and the update sends the event again; the consumer
 *       deduplicates on its {@code eventId}.</li>
 *   <li>The tenant is set per row for the logs and cleared after it (#42); the
 *       record's {@code X-Tenant-Id} header comes from the row, not from it.</li>
 *   <li>Runs on the scheduling thread, never on a request: a Kafka outage
 *       delays audit events, it no longer holds requests or connections.</li>
 * </ul>
 *
 * <p>Gauges {@code audit.outbox.pending} and
 * {@code audit.outbox.oldest.pending.age} (seconds) are read from the table
 * when Prometheus scrapes (at most every 5 s), on every replica, whether or
 * not this relay runs: a relay that stopped, or a break-glass command's event
 * left for a running service, shows as a growing age (found in review: when
 * the relay refreshed them, a stopped relay froze them). Counters
 * {@code audit.outbox.sent} and {@code audit.outbox.send.failed}.
 */
public class AuditOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(AuditOutboxRelay.class);

    static final Duration FIRST_RETRY = Duration.ofSeconds(5);
    static final Duration MAX_RETRY = Duration.ofMinutes(5);
    static final Duration BACKLOG_CACHE = Duration.ofSeconds(5);

    /** The relay's lock name is this plus the table (see {@link AuditOutbox#MAX_TABLE_NAME_LENGTH}). */
    static final String RELAY_LOCK_PREFIX = "audit-outbox-relay-";
    /** As long as {@link #RELAY_LOCK_PREFIX}, so one table-name limit serves both. */
    static final String PURGE_LOCK_PREFIX = "audit-outbox-purge-";

    /**
     * Statements one purge run makes at most, {@link AuditOutbox#PURGE_CHUNK}
     * rows each: 500,000 rows, far more than a service sends in the hour
     * between runs, and well inside the run's ten-minute lock.
     */
    static final int MAX_PURGE_CHUNKS = 100;

    /** What one run did. */
    public record RunResult(int sent, int failed) {
    }

    private final AuditOutbox outbox;
    private final AuditEventKafkaSender sender;
    private final AuditOutboxProperties properties;
    private final LockingTaskExecutor lockingTaskExecutor;
    private final Clock clock;
    private final String relayLockName;
    private final String purgeLockName;
    private final Counter sent;
    private final Counter failed;

    private volatile Instant pausedUntil = Instant.MIN;
    private final AtomicInteger consecutiveFailedRuns = new AtomicInteger();

    /** The backlog and when it was read, together (found in review: two fields could be read mismatched). */
    private record CachedBacklog(AuditOutbox.Backlog backlog, Instant readAt) {
    }

    private volatile CachedBacklog cachedBacklog;

    public AuditOutboxRelay(AuditOutbox outbox,
                            AuditEventKafkaSender sender,
                            AuditOutboxProperties properties,
                            LockProvider lockProvider,
                            MeterRegistry meterRegistry,
                            Clock clock) {
        this.outbox = outbox;
        this.sender = sender;
        this.properties = properties;
        this.lockingTaskExecutor = new DefaultLockingTaskExecutor(lockProvider);
        this.clock = clock;
        this.relayLockName = RELAY_LOCK_PREFIX + properties.table();
        this.purgeLockName = PURGE_LOCK_PREFIX + properties.table();
        this.sent = Counter.builder("audit.outbox.sent")
                .description("Audit events sent from the outbox and acknowledged by Kafka (backlog #0-84)")
                .register(meterRegistry);
        this.failed = Counter.builder("audit.outbox.send.failed")
                .description("Audit outbox send attempts that failed and will be retried (backlog #0-84)")
                .register(meterRegistry);
        Gauge.builder("audit.outbox.pending", this, relay -> relay.backlog()
                        .map(cached -> (double) cached.backlog().pending())
                        .orElse(Double.NaN))
                .description("Audit events waiting in the outbox (backlog #0-84)")
                .register(meterRegistry);
        Gauge.builder("audit.outbox.oldest.pending.age", this, AuditOutboxRelay::oldestPendingAgeSeconds)
                .description("Age of the oldest audit event waiting in the outbox (backlog #0-84)")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${audit.outbox.poll-interval-ms:2000}",
            initialDelayString = "${audit.outbox.poll-interval-ms:2000}")
    public void relay() {
        if (clock.instant().isBefore(pausedUntil)) {
            return;
        }
        relayNow();
    }

    /**
     * One run now, under the relay's lock, ignoring a pause: for the
     * break-glass command, which has no scheduler and sends the event it just
     * wrote before it exits when Kafka is reachable (backlog #0-84). Takes the
     * lock only when a row is due; a run with nothing due neither takes it nor
     * ends a pause's count of failed runs, as it tells nothing about Kafka.
     *
     * @return what the run did; nothing when no row is due or another
     *         instance holds the lock
     */
    public RunResult relayNow() {
        if (!outbox.anyDue()) {
            return new RunResult(0, 0);
        }
        try {
            final RunResult result = lockingTaskExecutor.executeWithLock(this::relayDue, new LockConfiguration(
                    clock.instant(), relayLockName, lockAtMostFor(), Duration.ZERO)).getResult();
            return result != null ? result : new RunResult(0, 0);
        } catch (Throwable e) {
            // executeWithLock declares Throwable; relayDue throws only unchecked
            // exceptions (a database error reading the outbox), let through.
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(e);
        }
    }

    @Scheduled(fixedDelayString = "${audit.outbox.purge-interval-ms:3600000}",
            initialDelayString = "${audit.outbox.purge-interval-ms:3600000}")
    public void purge() {
        lockingTaskExecutor.executeWithLock((Runnable) this::purgeSent, new LockConfiguration(
                clock.instant(), purgeLockName, Duration.ofMinutes(10), Duration.ZERO));
    }

    /**
     * One run: full batches until one is short, Kafka itself fails (a timeout,
     * a broker error) or the run's batches are used. A row Kafka refuses for
     * what it is (larger than a record may be) is backed off on its own and
     * neither ends the run nor pauses the relay.
     */
    RunResult relayDue() {
        int sentInRun = 0;
        int failedInRun = 0;
        for (int batch = 0; batch < properties.maxBatchesPerRun(); batch++) {
            final List<AuditOutbox.Pending> due = outbox.due(properties.batchSize());
            final BatchResult result = sendBatch(due);
            sentInRun += result.sent();
            failedInRun += result.failed();
            if (result.kafkaFailed()) {
                pauseAfterFailure();
                return new RunResult(sentInRun, failedInRun);
            }
            if (due.size() < properties.batchSize()) {
                break;
            }
        }
        consecutiveFailedRuns.set(0);
        pausedUntil = Instant.MIN;
        return new RunResult(sentInRun, failedInRun);
    }

    void purgeSent() {
        final AuditOutbox.Purge purge = outbox.purgeSentOlderThan(properties.retention(), MAX_PURGE_CHUNKS);
        if (purge.moreLeft()) {
            log.warn("Audit outbox purged {} sent events older than {}, the most one run deletes; "
                    + "the rest waits for the next run", purge.deleted(), properties.retention());
        } else if (purge.deleted() > 0) {
            log.info("Audit outbox purged {} sent events older than {}", purge.deleted(), properties.retention());
        }
    }

    /** 5 s, 10 s, 20 s, ... at most 5 min. */
    static Duration retryDelay(int attempt) {
        final int doublings = Math.min(Math.max(attempt - 1, 0), 10);
        final Duration delay = FIRST_RETRY.multipliedBy(1L << doublings);
        return delay.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : delay;
    }

    /** Until when scheduled runs are skipped; {@link Instant#MIN} when not paused. */
    Instant pausedUntil() {
        return pausedUntil;
    }

    private record BatchResult(int sent, int failed, boolean kafkaFailed) {
    }

    private BatchResult sendBatch(List<AuditOutbox.Pending> due) {
        final List<CompletableFuture<?>> acks = new ArrayList<>(due.size());
        for (final AuditOutbox.Pending row : due) {
            final CompletableFuture<?> ack = dispatch(row);
            acks.add(ack);
            if (ack.isCompletedExceptionally() && isKafkaFailure(ack)) {
                // No metadata, a broker gone: the next sends would wait for the
                // same, so they stay for the next run. A record refused for
                // itself (too large) does not stop the others (found in review:
                // one such row used to pause every tenant's events).
                break;
            }
        }
        final long deadline = System.nanoTime() + properties.sendTimeout().toNanos();
        final List<UUID> acknowledged = new ArrayList<>(acks.size());
        int failedRows = 0;
        boolean kafkaFailed = false;
        for (int i = 0; i < acks.size(); i++) {
            final AuditOutbox.Pending row = due.get(i);
            final Throwable failure = awaitAck(acks.get(i), deadline);
            if (failure == null) {
                acknowledged.add(row.id());
            } else {
                failedRows++;
                kafkaFailed |= isKafkaFailure(failure);
                withTenant(row, () -> markFailed(row, failure));
            }
        }
        sent.increment(acknowledged.size());
        failed.increment(failedRows);
        markSent(acknowledged);
        return new BatchResult(acknowledged.size(), failedRows, kafkaFailed);
    }

    /**
     * Whether a failure is Kafka's (retriable by Kafka's own classification, a
     * timeout or an interrupt) rather than the record's. Anything not known to
     * be the record's counts as Kafka's: pausing is the safe side.
     */
    static boolean isKafkaFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RetriableException
                    || cause instanceof TimeoutException
                    || cause instanceof InterruptedException) {
                return true;
            }
            if (cause instanceof RecordTooLargeException
                    || cause instanceof RecordBatchTooLargeException
                    || cause instanceof SerializationException
                    || cause instanceof InvalidRecordException
                    // Backlog #0-91: TenantContext / TenantRecords refuse a row
                    // whose tenant is not a valid tenant id; a fault of that row,
                    // not Kafka's. Only that type: any other bad argument is not
                    // known to be the row's, so it pauses (found in review).
                    || cause instanceof InvalidTenantIdException) {
                return false;
            }
        }
        return true;
    }

    private static boolean isKafkaFailure(CompletableFuture<?> failedAck) {
        try {
            failedAck.getNow(null);
            return false;
        } catch (CompletionException e) {
            return isKafkaFailure(e.getCause() != null ? e.getCause() : e);
        } catch (CancellationException e) {
            return true;
        }
    }

    /**
     * A send that throws instead of failing its future is a failed send of that
     * row. That includes {@link TenantContext#set} refusing the row's tenant
     * (backlog #0-92): it used to run before the {@code try}, so one such row
     * ended the whole run, for every tenant (found in review); now it is that
     * row's failure, backed off like a record Kafka refuses.
     */
    private CompletableFuture<?> dispatch(AuditOutbox.Pending row) {
        try {
            if (row.tenantId() != null) {
                TenantContext.set(row.tenantId());
            }
            return sender.sendForRelay(row.tenantId(), row.payload());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Runs {@code action} with the row's tenant in {@link TenantContext} for its
     * log lines, or without one when the tenant is not a valid tenant id: the
     * row's bookkeeping (marking it failed) must happen either way.
     */
    private static void withTenant(AuditOutbox.Pending row, Runnable action) {
        try {
            if (TenantIds.isValid(row.tenantId())) {
                TenantContext.set(row.tenantId());
            }
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    private static Throwable awaitAck(CompletableFuture<?> ack, long deadlineNanos) {
        try {
            ack.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return e;
        } catch (ExecutionException e) {
            return e.getCause() != null ? e.getCause() : e;
        } catch (TimeoutException e) {
            return e;
        }
    }

    /**
     * Kafka has these events; a failure here only leaves rows pending, so they
     * are sent again and deduplicated by the consumer. Not a send failure
     * (found in review: it used to be counted and logged as one).
     */
    private void markSent(List<UUID> acknowledged) {
        if (acknowledged.isEmpty()) {
            return;
        }
        try {
            final int marked = outbox.markSent(acknowledged);
            if (marked != acknowledged.size()) {
                // One writer per row (the relay, under its lock): rows no
                // longer pending mean the lock was lost mid-run or someone
                // edited the table.
                log.warn("Audit events sent but {} of {} outbox rows were no longer pending",
                        acknowledged.size() - marked, acknowledged.size());
            }
        } catch (RuntimeException e) {
            log.error("{} audit events sent but not marked sent; they will be sent again and deduplicated",
                    acknowledged.size(), e);
        }
    }

    private void markFailed(AuditOutbox.Pending row, Throwable failure) {
        final int attempt = row.attempts() + 1;
        final Duration wait = retryDelay(attempt);
        log.warn("Audit event not sent, retrying in {}: id={}, eventType={}, attempt={}",
                wait, row.id(), row.eventType(), attempt, failure);
        try {
            if (!outbox.markFailed(row.id(), wait, failure.toString())) {
                log.warn("Audit event failed but its outbox row was no longer pending: id={}, eventType={}",
                        row.id(), row.eventType());
            }
        } catch (RuntimeException e) {
            // The row stays due and is tried again on the next run.
            log.error("Audit outbox row could not be marked failed: id={}, eventType={}",
                    row.id(), row.eventType(), e);
        }
    }

    private void pauseAfterFailure() {
        final int failedRuns = consecutiveFailedRuns.incrementAndGet();
        final Duration pause = retryDelay(failedRuns);
        pausedUntil = clock.instant().plus(pause);
        log.warn("Audit outbox relay paused for {} after Kafka failed a send (failed runs in a row: {})",
                pause, failedRuns);
    }

    /** The table's backlog, read at most every {@link #BACKLOG_CACHE}; empty when it cannot be read. */
    private Optional<CachedBacklog> backlog() {
        final Instant now = clock.instant();
        final CachedBacklog cached = cachedBacklog;
        if (cached != null && now.isBefore(cached.readAt().plus(BACKLOG_CACHE))) {
            return Optional.of(cached);
        }
        try {
            final CachedBacklog read = new CachedBacklog(outbox.backlog(), now);
            cachedBacklog = read;
            return Optional.of(read);
        } catch (RuntimeException e) {
            // NaN in the gauges; the database being unreachable has its own
            // signals (health, the service's errors).
            log.warn("Audit outbox backlog could not be read for the metrics: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * The oldest pending event's age as the database measured it at the last
     * read, plus the time since that read: between reads the age keeps
     * growing, and only an elapsed time is taken from this process's clock.
     */
    private double oldestPendingAgeSeconds() {
        return backlog()
                .map(cached -> cached.backlog().oldestAge()
                        .map(age -> (double) age.plus(Duration.between(cached.readAt(), clock.instant())).toSeconds())
                        .orElse(0.0))
                .orElse(Double.NaN);
    }

    /**
     * Long enough for a full run: each batch waits at most the send timeout,
     * plus the producer's own blocking and the database work. The producer
     * blocks at most {@code max.block.ms} once per run (a run stops at the
     * first send Kafka fails), so the lock must also outlast that block plus
     * one send timeout: 5 s in every service but escalation-service, which
     * keeps Kafka's 60 s and relies on the default 80 s (see its
     * application.yml).
     */
    private Duration lockAtMostFor() {
        return properties.sendTimeout().multipliedBy(properties.maxBatchesPerRun()).plusSeconds(30);
    }
}
