package com.incidentplatform.auth.service;

import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Isolates the short, independent database writes involved in processing
 * the auth email outbox from the real SMTP call that sits between them.
 *
 * <h2>Fixed: one transaction spanning an entire batch of SMTP sends</h2>
 * {@code AuthEmailScheduler.processPending()}/{@code retryFailed()}
 * previously wrapped their whole loop — including every
 * {@code AuthEmailService.sendInviteEmail}/{@code sendPasswordResetEmail}
 * call inside it, real blocking {@code JavaMailSender.send()} SMTP calls —
 * in a single {@code @Transactional}. That held a database connection open
 * for the cumulative duration of every SMTP send in the batch, not just
 * one. Mirrors the exact anti-pattern {@code postmortem-service}'s
 * {@code PostmortemPersistenceService} was built to avoid (see that
 * class's own Javadoc) — same fix applied here: each database write below
 * is its own short, independent transaction, acquiring and releasing a
 * connection in milliseconds, with no transaction open while the scheduler
 * is waiting on SMTP.
 */
@Service
public class AuthEmailPersistenceService {

    private static final Logger log =
            LoggerFactory.getLogger(AuthEmailPersistenceService.class);

    private final AuthEmailOutboxRepository outboxRepository;

    public AuthEmailPersistenceService(AuthEmailOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    /** Marks the entry SENT after a successful SMTP dispatch. */
    @Transactional
    public void markSent(UUID entryId) {
        outboxRepository.findById(entryId).ifPresentOrElse(entry -> {
            entry.markSent();
            outboxRepository.save(entry);
        }, () -> log.warn("markSent: outbox entry no longer exists — " +
                "skipping: entryId={}", entryId));
    }

    /**
     * Marks the entry FAILED — a transient failure the scheduler will
     * retry later, not yet at {@code maxRetryAttempts}.
     */
    @Transactional
    public void markFailed(UUID entryId, String errorMessage) {
        outboxRepository.findById(entryId).ifPresentOrElse(entry -> {
            entry.markFailed(errorMessage);
            outboxRepository.save(entry);
        }, () -> log.warn("markFailed: outbox entry no longer exists — " +
                "skipping: entryId={}", entryId));
    }

    /**
     * Marks the entry PERMANENTLY_FAILED — retry budget exhausted, or the
     * entry has no raw token and can never be sent. Requires manual
     * investigation; the scheduler will not pick this entry up again.
     */
    @Transactional
    public void markPermanentlyFailed(UUID entryId, String errorMessage) {
        outboxRepository.findById(entryId).ifPresentOrElse(entry -> {
            entry.markPermanentlyFailed(errorMessage);
            outboxRepository.save(entry);
        }, () -> log.warn("markPermanentlyFailed: outbox entry no longer exists — " +
                "skipping: entryId={}", entryId));
    }
}