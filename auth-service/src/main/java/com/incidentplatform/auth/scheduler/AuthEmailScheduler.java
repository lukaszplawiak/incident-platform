package com.incidentplatform.auth.scheduler;

import com.incidentplatform.auth.config.InviteEmailProperties;
import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.exception.InviteEmailException;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.service.AuthEmailPersistenceService;
import com.incidentplatform.auth.service.AuthEmailService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Processes the auth email outbox — picks up PENDING and FAILED entries
 * and sends the actual emails (invite or password reset).
 *
 * <h2>Outbox Pattern</h2>
 * Writers ({@code UserService}, {@code ForgotPasswordService}) create PENDING
 * entries and return immediately. This scheduler runs in a dedicated
 * scheduled thread, completely decoupled from the HTTP request lifecycle.
 *
 * <h2>Two processing paths</h2>
 * <ul>
 *   <li><b>PENDING</b> — dispatched by {@link #processPending()} every 30s</li>
 *   <li><b>FAILED</b> — retried by {@link #retryFailed()} every 5 minutes</li>
 * </ul>
 *
 * <h2>Email type routing</h2>
 * The {@code emailType} field on each entry determines which template and
 * link path to use — {@link AuthEmailType#INVITE} → accept-invite,
 * {@link AuthEmailType#PASSWORD_RESET} → reset-password.
 *
 * <h2>ShedLock</h2>
 * Both methods are protected by ShedLock to prevent duplicate sends when
 * multiple auth-service instances are running.
 *
 * <h2>Fixed: no single transaction spans a whole batch of SMTP sends</h2>
 * {@link #processPending()}/{@link #retryFailed()} previously carried
 * {@code @Transactional} on the whole method — including the loop that
 * called {@code AuthEmailService.sendInviteEmail}/{@code sendPasswordResetEmail},
 * real blocking SMTP calls — holding a database connection open for the
 * combined duration of every send in the batch. Fixed by removing
 * {@code @Transactional} from both scheduled methods entirely: the initial
 * repository read is already its own short, auto-committing transaction
 * (Spring Data JPA's default behaviour on repository query methods), and
 * each outcome is now written via {@link AuthEmailPersistenceService} —
 * a short, independent transaction per entry, with no transaction open
 * while waiting on SMTP. Same pattern already established in
 * {@code postmortem-service}'s {@code PostmortemPersistenceService}.
 */
@Component
@EnableConfigurationProperties(InviteEmailProperties.class)
public class AuthEmailScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(AuthEmailScheduler.class);

    private final AuthEmailOutboxRepository outboxRepository;
    private final AuthEmailService emailService;
    private final AuthEmailPersistenceService persistenceService;
    private final int maxRetryAttempts;
    private final Duration pendingThreshold;
    private final int batchSize;

    public AuthEmailScheduler(AuthEmailOutboxRepository outboxRepository,
                              AuthEmailService emailService,
                              AuthEmailPersistenceService persistenceService,
                              InviteEmailProperties properties) {
        this.outboxRepository  = outboxRepository;
        this.emailService      = emailService;
        this.persistenceService = persistenceService;
        this.maxRetryAttempts  = properties.maxRetryAttempts();
        this.pendingThreshold  = properties.pendingThreshold();
        this.batchSize         = properties.batchSize();
    }

    /**
     * Fixed (backlog #54): batch size cap — previously processed every
     * matching row unconditionally. See
     * {@link InviteEmailProperties#batchSize()}'s Javadoc for why an
     * unbounded batch here was a genuine risk given each item's real
     * SMTP send cost. Any excess beyond {@code batchSize} is simply
     * picked up on the next scheduler run rather than attempted in this one.
     */
    @Scheduled(
            fixedDelayString = "${invite.email.scheduler-interval-ms:30000}",
            initialDelayString = "30000"
    )
    @SchedulerLock(
            name = "auth-service:processPendingAuthEmails",
            lockAtMostFor = "4m",
            lockAtLeastFor = "10s"
    )
    public void processPending() {
        final Instant threshold = Instant.now().minus(pendingThreshold);
        final List<AuthEmailOutbox> pending = outboxRepository.findPendingOlderThan(
                threshold, null, PageRequest.of(0, batchSize));

        if (pending.isEmpty()) {
            log.debug("Auth email outbox: no PENDING entries");
            return;
        }

        log.info("Auth email outbox: processing {} PENDING entries",
                pending.size());

        for (final AuthEmailOutbox entry : pending) {
            processOne(entry);
        }
    }

    /**
     * Fixed (backlog #54): batch size cap — same reasoning as
     * {@link #processPending}'s fix.
     */
    @Scheduled(
            fixedDelayString = "${invite.email.retry-interval-ms:300000}",
            initialDelayString = "120000"
    )
    @SchedulerLock(
            name = "auth-service:retryFailedAuthEmails",
            lockAtMostFor = "4m",
            lockAtLeastFor = "10s"
    )
    public void retryFailed() {
        final List<AuthEmailOutbox> failed = outboxRepository.findFailedWithRemainingRetries(
                maxRetryAttempts, null, PageRequest.of(0, batchSize));

        if (failed.isEmpty()) {
            log.debug("Auth email outbox: no FAILED entries with remaining retries");
            return;
        }

        log.info("Auth email outbox: retrying {} FAILED entries", failed.size());

        for (final AuthEmailOutbox entry : failed) {
            processOne(entry);
        }
    }

    private void processOne(AuthEmailOutbox entry) {
        final java.util.UUID entryId = entry.getId();
        final String email = entry.getEmail();

        if (entry.getRawToken() == null) {
            log.error("Auth email outbox entry has null rawToken — " +
                            "skipping: entryId={}, email={}, type={}, status={}",
                    entryId, email, entry.getEmailType(), entry.getStatus());
            persistenceService.markPermanentlyFailed(
                    entryId, "rawToken is null — cannot send email");
            return;
        }

        try {
            // Route to correct email template based on type — the real,
            // blocking SMTP call. No transaction is open during this call.
            switch (entry.getEmailType()) {
                case INVITE ->
                        emailService.sendInviteEmail(email, entry.getRawToken());
                case PASSWORD_RESET ->
                        emailService.sendPasswordResetEmail(email, entry.getRawToken());
            }

            persistenceService.markSent(entryId);

            log.info("Auth email sent: type={}, email={}, userId={}, attempt={}",
                    entry.getEmailType(), email,
                    entry.getUser().getId(), entry.getRetryCount() + 1);

        } catch (InviteEmailException e) {
            final int newRetryCount = entry.getRetryCount() + 1;

            if (newRetryCount >= maxRetryAttempts) {
                persistenceService.markPermanentlyFailed(entryId, e.getMessage());
                log.error("Auth email permanently failed after {} attempts: " +
                                "type={}, email={}, userId={}, error={}",
                        maxRetryAttempts, entry.getEmailType(),
                        email, entry.getUser().getId(), e.getMessage());
            } else {
                persistenceService.markFailed(entryId, e.getMessage());
                log.warn("Auth email failed (attempt {}/{}), will retry: " +
                                "type={}, email={}, error={}",
                        newRetryCount, maxRetryAttempts,
                        entry.getEmailType(), email, e.getMessage());
            }
        } catch (Exception e) {
            log.error("Unexpected error processing auth email outbox entry: " +
                            "entryId={}, type={}, email={}, error={}",
                    entryId, entry.getEmailType(), email, e.getMessage(), e);
            persistenceService.markFailed(
                    entryId, "Unexpected error: " + e.getMessage());
        }
    }
}