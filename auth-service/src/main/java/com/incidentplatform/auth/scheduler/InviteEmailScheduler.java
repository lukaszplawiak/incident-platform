package com.incidentplatform.auth.scheduler;

import com.incidentplatform.auth.domain.InviteEmailOutbox;
import com.incidentplatform.auth.repository.InviteEmailOutboxRepository;
import com.incidentplatform.auth.exception.InviteEmailException;
import com.incidentplatform.auth.service.InviteEmailService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Processes the invite email outbox — picks up PENDING and FAILED entries
 * and sends the actual invite emails.
 *
 * <h2>Outbox Pattern — why this scheduler exists</h2>
 * {@code UserService.createUser()} writes a PENDING outbox entry and returns
 * immediately — no email is sent on the HTTP thread. This scheduler runs in
 * a dedicated scheduled thread and makes the actual SMTP call, completely
 * decoupled from the HTTP request lifecycle.
 *
 * <h2>Two processing paths</h2>
 * <ol>
 *   <li><b>PENDING</b> — fresh entries written by UserService.
 *       Picked up by {@link #processPending()} every 30 seconds.</li>
 *   <li><b>FAILED</b> — entries that failed on a previous attempt and
 *       still have retry budget. Picked up by {@link #retryFailed()}
 *       every 5 minutes.</li>
 * </ol>
 *
 * <h2>raw_token lifecycle</h2>
 * On successful send, {@link InviteEmailOutbox#markSent()} NULLs the
 * raw token — only the SHA-256 hash in {@code auth_tokens} remains.
 * On permanent failure, {@link InviteEmailOutbox#markPermanentlyFailed}
 * also NULLs the token — admin must resend the invite to restart the flow.
 *
 * <h2>ShedLock</h2>
 * Both methods are protected by ShedLock to prevent duplicate sends when
 * multiple auth-service instances are running.
 */
@Component
public class InviteEmailScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(InviteEmailScheduler.class);

    private final InviteEmailOutboxRepository outboxRepository;
    private final InviteEmailService emailService;
    private final int maxRetryAttempts;
    private final Duration pendingThreshold;

    public InviteEmailScheduler(
            InviteEmailOutboxRepository outboxRepository,
            InviteEmailService emailService,
            @Value("${invite.email.max-retry-attempts:3}")
            int maxRetryAttempts,
            @Value("${invite.email.pending-threshold-seconds:30}")
            int pendingThresholdSeconds) {
        this.outboxRepository = outboxRepository;
        this.emailService = emailService;
        this.maxRetryAttempts = maxRetryAttempts;
        this.pendingThreshold = Duration.ofSeconds(pendingThresholdSeconds);
    }

    /**
     * Picks up PENDING outbox entries and sends invite emails.
     *
     * <p>Only processes entries older than {@code pendingThreshold} (default
     * 30 seconds) to avoid racing against a UserService that just committed
     * an entry within the same scheduler cycle.
     */
    @Scheduled(
            fixedDelayString = "${invite.email.scheduler-interval-ms:30000}",
            initialDelayString = "30000"
    )
    @SchedulerLock(
            name = "auth-service:processPendingInviteEmails",
            lockAtMostFor = "4m",
            lockAtLeastFor = "10s"
    )
    @Transactional
    public void processPending() {
        final Instant threshold = Instant.now().minus(pendingThreshold);
        final List<InviteEmailOutbox> pending =
                outboxRepository.findPendingOlderThan(threshold);

        if (pending.isEmpty()) {
            log.debug("Invite email outbox: no PENDING entries");
            return;
        }

        log.info("Invite email outbox: processing {} PENDING entries",
                pending.size());

        for (final InviteEmailOutbox entry : pending) {
            processOne(entry);
        }
    }

    /**
     * Retries FAILED outbox entries that still have remaining retry budget.
     */
    @Scheduled(
            fixedDelayString = "${invite.email.retry-interval-ms:300000}",
            initialDelayString = "120000"
    )
    @SchedulerLock(
            name = "auth-service:retryFailedInviteEmails",
            lockAtMostFor = "4m",
            lockAtLeastFor = "10s"
    )
    @Transactional
    public void retryFailed() {
        final List<InviteEmailOutbox> failed =
                outboxRepository.findFailedWithRemainingRetries(maxRetryAttempts);

        if (failed.isEmpty()) {
            log.debug("Invite email outbox: no FAILED entries with remaining retries");
            return;
        }

        log.info("Invite email outbox: retrying {} FAILED entries", failed.size());

        for (final InviteEmailOutbox entry : failed) {
            processOne(entry);
        }
    }

    private void processOne(InviteEmailOutbox entry) {
        final String email = entry.getEmail();

        // Raw token should never be null for PENDING/FAILED entries.
        // If it is, the entry is corrupted — skip and log.
        if (entry.getRawToken() == null) {
            log.error("Invite email outbox entry has null rawToken — " +
                            "skipping: entryId={}, email={}, status={}",
                    entry.getId(), email, entry.getStatus());
            entry.markPermanentlyFailed("rawToken is null — cannot send email");
            outboxRepository.save(entry);
            return;
        }

        try {
            emailService.sendInviteEmail(email, entry.getRawToken());

            // markSent() NULLs raw_token — security property restored
            entry.markSent();
            outboxRepository.save(entry);

            log.info("Invite email sent: email={}, userId={}, attempt={}",
                    email, entry.getUser().getId(), entry.getRetryCount() + 1);

        } catch (InviteEmailException e) {
            final int newRetryCount = entry.getRetryCount() + 1;

            if (newRetryCount >= maxRetryAttempts) {
                // markPermanentlyFailed() also NULLs raw_token
                entry.markPermanentlyFailed(e.getMessage());
                outboxRepository.save(entry);

                log.error("Invite email permanently failed after {} attempts: " +
                                "email={}, userId={}, lastError={}",
                        maxRetryAttempts, email,
                        entry.getUser().getId(), e.getMessage());
            } else {
                entry.markFailed(e.getMessage());
                outboxRepository.save(entry);

                log.warn("Invite email failed (attempt {}/{}), will retry: " +
                                "email={}, error={}",
                        newRetryCount, maxRetryAttempts, email, e.getMessage());
            }
        } catch (Exception e) {
            log.error("Unexpected error processing invite email outbox entry: " +
                            "entryId={}, email={}, error={}",
                    entry.getId(), email, e.getMessage(), e);

            entry.markFailed("Unexpected error: " + e.getMessage());
            outboxRepository.save(entry);
        }
    }
}