package com.incidentplatform.auth.scheduler;

import com.incidentplatform.auth.config.InviteEmailProperties;
import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailStatus;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.exception.InviteEmailException;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.service.AuthEmailPersistenceService;
import com.incidentplatform.auth.service.AuthEmailPersistenceService.Attempt;
import com.incidentplatform.auth.service.AuthEmailService;
import com.incidentplatform.shared.security.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sends the auth email outbox — invites and password resets — and is the only
 * writer of an outbox row after it was requested (backlog #0-52).
 *
 * <h2>Outbox Pattern</h2>
 * Request paths ({@code UserService}, {@code ResendInviteService},
 * {@code ForgotPasswordService}, through {@code AuthEmailRequestService}) only
 * insert a PENDING row and return. This scheduler sends it in a dedicated
 * scheduled thread, decoupled from the HTTP request lifecycle.
 *
 * <h2>One attempt (backlog #0-52)</h2>
 * For each due entry: {@code AuthEmailPersistenceService.prepareAttempt}
 * closes it if it is no longer worth sending (SUPERSEDED: a newer request, an
 * accepted invite, a missing user; PERMANENTLY_FAILED: its deadline passed) or
 * else creates a fresh token — invalidating the user's earlier ones of that
 * type — and commits it. The email is sent with that token, and the outcome is
 * recorded. The token is created at send time, so the link is valid for its
 * full lifetime from the moment it goes out, and no raw token is ever stored.
 *
 * <h2>At least once, not exactly once</h2>
 * If the process dies after SMTP accepted an email but before its outcome is
 * recorded, the entry is still due and the next run sends it again: the next
 * {@code prepareAttempt} invalidates the token already delivered and creates a
 * new one. The user gets two emails and only the newer link works, so no two
 * links are ever valid at once — accepted, as for every outbox here, since
 * SMTP offers no idempotency key to deduplicate on.
 *
 * <h2>Two lanes, retried until the deadline (backlog #0-52)</h2>
 * {@link #processPending()} sends new entries and {@link #retryFailed()}
 * retries failed ones, both every 30 s and both only for entries whose
 * {@code nextAttemptAt} has come. Each lane has its own batch, so a backlog of
 * retries after an SMTP outage cannot delay new emails (a password reset is
 * worth sending for 15 minutes). {@code retryFailed()} used to run every 5
 * minutes and give up after 3 attempts, losing every email of an outage longer
 * than about 15 minutes; a failed send is now retried on
 * {@link AuthEmailRetryPolicy}'s schedule until the entry's deadline.
 *
 * <h2>Processing budget (backlog #0-52, performance review)</h2>
 * A run stops taking entries after {@code processing-budget}. SMTP timeouts
 * apply per socket operation and one email is about ten of them, so a slow
 * server can hold one send for close to a minute; a batch cap alone could let
 * a run outlive its ShedLock and a second replica send the same entries. The
 * budget must leave the lock {@link #LOCK_MARGIN} for the send in flight,
 * checked at startup — the pattern of notification-service's
 * {@code NotificationScheduler}.
 *
 * <h2>Metrics (backlog #0-52)</h2>
 * {@code auth.email.send{type,outcome=sent|failed}} for every SMTP attempt
 * and {@code auth.email.permanently_failed{type,reason}} for every email given
 * up; the alerts {@code AuthEmailDeliveryFailing} and
 * {@code AuthEmailPermanentlyFailed} (docker/prometheus.rules.yml) read them.
 * Counters, so an alert fires on the event itself rather than on rows that the
 * retention purge later removes. Every tag combination is registered at
 * start-up so the series exist at 0 and {@code increase()} sees the first one.
 *
 * <h2>ShedLock</h2>
 * Each job holds its own lock, so replicas never run the same lane twice.
 *
 * <h2>Fixed (backlog #55): TenantContext per entry</h2>
 * {@link TenantContext} is set from the entry's own tenant for the duration of
 * each entry and cleared in {@code finally}, so every log line — including
 * those in {@code AuthEmailPersistenceService}/{@code AuthEmailService} —
 * carries the right tenant via MDC, as in every other scheduler here.
 *
 * <h2>Fixed: no single transaction spans a whole batch of SMTP sends</h2>
 * The scheduled methods are not {@code @Transactional}: each database step is
 * its own short transaction in {@code AuthEmailPersistenceService}, and none is
 * open while waiting on SMTP.
 */
@Component
@EnableConfigurationProperties(InviteEmailProperties.class)
public class AuthEmailScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(AuthEmailScheduler.class);

    /** ShedLock {@code lockAtMostFor} of both lanes; keep the two in step. */
    static final String LOCK_AT_MOST_FOR = "4m";
    static final Duration LOCK_AT_MOST_FOR_DURATION = Duration.ofMinutes(4);

    /**
     * ShedLock {@code lockAtMostFor} of the purge job: one DELETE, not bound by
     * the lanes' processing budget, so it does not follow {@link #LOCK_AT_MOST_FOR}.
     */
    static final String PURGE_LOCK_AT_MOST_FOR = "4m";

    /** Room left for the send in flight when the budget runs out — about one slow SMTP exchange. */
    static final Duration LOCK_MARGIN = Duration.ofMinutes(1);

    static final String SEND_COUNTER = "auth.email.send";
    static final String PERMANENTLY_FAILED_COUNTER = "auth.email.permanently_failed";

    /** Why an email was given up — the {@code reason} tag of {@link #PERMANENTLY_FAILED_COUNTER}. */
    enum GiveUpReason {
        /** Sends kept failing until the deadline (backlog #0-52). */
        RETRY_WINDOW_EXHAUSTED,
        /** The deadline passed before the entry could be tried (e.g. the scheduler was down). */
        DEADLINE_PASSED
    }

    private final AuthEmailOutboxRepository outboxRepository;
    private final AuthEmailService emailService;
    private final AuthEmailPersistenceService persistenceService;
    private final AuthEmailRetryPolicy retryPolicy;
    private final int batchSize;
    private final Duration processingBudget;
    private final Duration retention;
    private final Duration deadlineTolerance;
    private final Map<AuthEmailType, Counter> sentCounters = new EnumMap<>(AuthEmailType.class);
    private final Map<AuthEmailType, Counter> failedCounters = new EnumMap<>(AuthEmailType.class);
    private final Map<AuthEmailType, Map<GiveUpReason, Counter>> giveUpCounters =
            new EnumMap<>(AuthEmailType.class);

    public AuthEmailScheduler(AuthEmailOutboxRepository outboxRepository,
                              AuthEmailService emailService,
                              AuthEmailPersistenceService persistenceService,
                              InviteEmailProperties properties,
                              MeterRegistry meterRegistry) {
        this.outboxRepository   = outboxRepository;
        this.emailService       = emailService;
        this.persistenceService = persistenceService;
        this.retryPolicy        = new AuthEmailRetryPolicy(properties);
        this.batchSize          = properties.batchSize();
        this.processingBudget   = validated(properties.processingBudget());
        this.retention          = properties.retention();
        // An attempt scheduled for the deadline itself is picked up at most one
        // interval later, and may wait behind a run's whole budget.
        this.deadlineTolerance  = Duration.ofMillis(properties.schedulerIntervalMs())
                .plus(processingBudget);

        for (final AuthEmailType type : AuthEmailType.values()) {
            sentCounters.put(type, sendCounter(meterRegistry, type, "sent"));
            failedCounters.put(type, sendCounter(meterRegistry, type, "failed"));
            final Map<GiveUpReason, Counter> byReason = new EnumMap<>(GiveUpReason.class);
            for (final GiveUpReason reason : GiveUpReason.values()) {
                byReason.put(reason, Counter.builder(PERMANENTLY_FAILED_COUNTER)
                        .description("Auth emails given up without being sent (backlog #0-52)")
                        .tag("type", type.name())
                        .tag("reason", reason.name())
                        .register(meterRegistry));
            }
            giveUpCounters.put(type, byReason);
        }
    }

    /**
     * Fails at startup if the processing budget would let a run outlive the
     * ShedLock (see "Processing budget" above).
     */
    static Duration validated(Duration budget) {
        final Duration limit = LOCK_AT_MOST_FOR_DURATION.minus(LOCK_MARGIN);
        if (budget.isZero() || budget.isNegative() || budget.compareTo(limit) > 0) {
            throw new IllegalArgumentException(
                    "invite.email.processing-budget must be positive and at most " + limit
                            + " (the lock duration " + LOCK_AT_MOST_FOR_DURATION + " minus a "
                            + LOCK_MARGIN + " margin for the send in flight), was " + budget);
        }
        return budget;
    }

    private static Counter sendCounter(MeterRegistry registry, AuthEmailType type, String outcome) {
        return Counter.builder(SEND_COUNTER)
                .description("Auth email SMTP send attempts by outcome (backlog #0-52)")
                .tag("type", type.name())
                .tag("outcome", outcome)
                .register(registry);
    }

    /** New requests. Fixed (backlog #54): capped at {@code batch-size} per run. */
    @Scheduled(
            fixedDelayString = "${invite.email.scheduler-interval-ms:30000}",
            initialDelayString = "30000"
    )
    @SchedulerLock(
            name = "auth-service:processPendingAuthEmails",
            lockAtMostFor = LOCK_AT_MOST_FOR,
            lockAtLeastFor = "10s"
    )
    public void processPending() {
        processBatch("PENDING",
                outboxRepository.findDuePending(Instant.now(), PageRequest.of(0, batchSize)));
    }

    /**
     * Retries. Runs as often as {@link #processPending}: each entry's own
     * {@code nextAttemptAt} is the retry schedule, not this interval.
     */
    @Scheduled(
            fixedDelayString = "${invite.email.scheduler-interval-ms:30000}",
            initialDelayString = "45000"
    )
    @SchedulerLock(
            name = "auth-service:retryFailedAuthEmails",
            lockAtMostFor = LOCK_AT_MOST_FOR,
            lockAtLeastFor = "10s"
    )
    public void retryFailed() {
        processBatch("FAILED",
                outboxRepository.findDueFailed(Instant.now(), PageRequest.of(0, batchSize)));
    }

    /**
     * Deletes terminal entries older than {@code retention} (backlog #0-52):
     * since the outbox no longer references tokens, token cleanup does not
     * remove its rows any more.
     */
    @Scheduled(
            fixedDelayString = "${invite.email.purge-interval-ms:86400000}",
            initialDelayString = "120000"
    )
    @SchedulerLock(
            name = "auth-service:purgeAuthEmailOutbox",
            lockAtMostFor = PURGE_LOCK_AT_MOST_FOR,
            lockAtLeastFor = "10s"
    )
    @Transactional
    public void purgeTerminal() {
        final int deleted = outboxRepository.deleteTerminalCreatedBefore(Instant.now().minus(retention));
        if (deleted > 0) {
            log.info("Auth email outbox: purged {} terminal entries older than {}", deleted, retention);
        }
    }

    private void processBatch(String lane, List<AuthEmailOutbox> due) {
        if (due.isEmpty()) {
            log.debug("Auth email outbox: no {} entries due", lane);
            return;
        }
        log.info("Auth email outbox: processing {} due {} entries", due.size(), lane);

        final Instant budgetEnd = Instant.now().plus(processingBudget);
        int processed = 0;
        for (final AuthEmailOutbox entry : due) {
            if (processed > 0 && Instant.now().isAfter(budgetEnd)) {
                log.warn("Auth email outbox: processing budget of {} used up — {} of {} {} entries "
                        + "are left for the next run", processingBudget, due.size() - processed,
                        due.size(), lane);
                break;
            }
            processed++;
            // Fixed (backlog #55): see this class's own Javadoc.
            TenantContext.set(entry.getTenantId());
            try {
                processOne(entry);
            } catch (RuntimeException e) {
                // A database error on this entry must not stop the batch; the
                // entry stays PENDING or FAILED and is picked up again.
                log.error("Auth email outbox: could not process entryId={}, type={} — "
                        + "will be retried", entry.getId(), entry.getEmailType(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    /**
     * <h2>Fixed (backlog #81): every failure reaches a terminal state</h2>
     * One catch for every exception from the send, not only
     * {@link InviteEmailException}: an unexpected error (e.g. a template bug)
     * used to be retried outside the attempt ceiling and could stay FAILED
     * forever. The ceiling is now the deadline, applied to every failure alike,
     * and V19's CHECK keeps every FAILED row visible to
     * {@link AuthEmailOutboxRepository#findDueFailed}.
     */
    private void processOne(AuthEmailOutbox entry) {
        final Instant now = Instant.now();
        final Attempt attempt = persistenceService.prepareAttempt(entry, now, deadlineTolerance);

        switch (attempt) {
            case Attempt.AlreadyClosed ignored ->
                    log.info("Auth email outbox entry was already closed — skipped: entryId={}, type={}",
                            entry.getId(), entry.getEmailType());
            case Attempt.Closed closed when closed.status() == AuthEmailStatus.SUPERSEDED ->
                    log.info("Auth email not sent, no longer needed ({}): entryId={}, type={}, userId={}",
                            closed.reason(), entry.getId(), entry.getEmailType(), entry.getUserId());
            case Attempt.Closed closed -> {
                giveUpCounters.get(entry.getEmailType()).get(GiveUpReason.DEADLINE_PASSED).increment();
                log.error("Auth email permanently failed ({}): {}, entryId={}, type={}, email={}, userId={}",
                        GiveUpReason.DEADLINE_PASSED, closed.reason(), entry.getId(),
                        entry.getEmailType(), entry.getEmail(), entry.getUserId());
            }
            case Attempt.Send send -> send(entry, send);
        }
    }

    private void send(AuthEmailOutbox entry, Attempt.Send send) {
        final AuthEmailType type = entry.getEmailType();
        final int attemptNumber = entry.getAttempts() + 1;
        try {
            // The real, blocking SMTP call. No transaction is open during it.
            switch (type) {
                case INVITE -> emailService.sendInviteEmail(entry.getEmail(), send.rawToken());
                case PASSWORD_RESET -> emailService.sendPasswordResetEmail(entry.getEmail(), send.rawToken());
            }
        } catch (Exception e) {
            failedCounters.get(type).increment();
            handleSendFailure(entry, send, attemptNumber, e);
            return;
        }

        sentCounters.get(type).increment();
        if (persistenceService.recordSent(entry.getId(), Instant.now())) {
            log.info("Auth email sent: type={}, email={}, userId={}, attempt={}",
                    type, entry.getEmail(), entry.getUserId(), attemptNumber);
        } else {
            log.info("Auth email sent, but its entry had already been closed — not recorded: "
                    + "entryId={}, type={}", entry.getId(), type);
        }
    }

    private void handleSendFailure(AuthEmailOutbox entry, Attempt.Send send, int failures, Exception e) {
        final String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        final Instant now = Instant.now();
        final Optional<Instant> next = retryPolicy.nextAttempt(failures, now, entry.getDeadline());

        if (next.isPresent()) {
            if (persistenceService.recordFailed(entry.getId(), send.tokenId(), error, next.get(), now)) {
                log.warn("Auth email failed (attempt {}), next attempt at {} (deadline {}): "
                                + "type={}, email={}, error={}", failures, next.get(),
                        entry.getDeadline(), entry.getEmailType(), entry.getEmail(), error);
            } else {
                log.info("Auth email failed, but its entry had already been closed — no further "
                        + "attempt: entryId={}, type={}", entry.getId(), entry.getEmailType());
            }
            return;
        }

        if (persistenceService.recordGivenUp(entry.getId(), send.tokenId(), error, now)) {
            giveUpCounters.get(entry.getEmailType()).get(GiveUpReason.RETRY_WINDOW_EXHAUSTED).increment();
            log.error("Auth email permanently failed ({}) after {} failed attempt(s): "
                            + "type={}, email={}, userId={}, error={}",
                    GiveUpReason.RETRY_WINDOW_EXHAUSTED, failures, entry.getEmailType(),
                    entry.getEmail(), entry.getUserId(), error);
        } else {
            log.info("Auth email failed, but its entry had already been closed — not given up: "
                    + "entryId={}, type={}", entry.getId(), entry.getEmailType());
        }
    }
}
