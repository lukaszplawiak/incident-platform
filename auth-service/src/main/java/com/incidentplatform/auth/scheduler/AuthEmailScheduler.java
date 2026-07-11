package com.incidentplatform.auth.scheduler;

import com.incidentplatform.auth.config.InviteEmailProperties;
import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.exception.InviteEmailException;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.service.AuthEmailService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Component
@EnableConfigurationProperties(InviteEmailProperties.class)
public class AuthEmailScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(AuthEmailScheduler.class);

    private final AuthEmailOutboxRepository outboxRepository;
    private final AuthEmailService emailService;
    private final int maxRetryAttempts;
    private final Duration pendingThreshold;

    public AuthEmailScheduler(AuthEmailOutboxRepository outboxRepository,
                              AuthEmailService emailService,
                              InviteEmailProperties properties) {
        this.outboxRepository = outboxRepository;
        this.emailService     = emailService;
        this.maxRetryAttempts = properties.maxRetryAttempts();
        this.pendingThreshold = properties.pendingThreshold();
    }

    @Scheduled(
            fixedDelayString = "${invite.email.scheduler-interval-ms:30000}",
            initialDelayString = "30000"
    )
    @SchedulerLock(
            name = "auth-service:processPendingAuthEmails",
            lockAtMostFor = "4m",
            lockAtLeastFor = "10s"
    )
    @Transactional
    public void processPending() {
        final Instant threshold = Instant.now().minus(pendingThreshold);
        final List<AuthEmailOutbox> pending =
                outboxRepository.findPendingOlderThan(threshold, null);

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

    @Scheduled(
            fixedDelayString = "${invite.email.retry-interval-ms:300000}",
            initialDelayString = "120000"
    )
    @SchedulerLock(
            name = "auth-service:retryFailedAuthEmails",
            lockAtMostFor = "4m",
            lockAtLeastFor = "10s"
    )
    @Transactional
    public void retryFailed() {
        final List<AuthEmailOutbox> failed =
                outboxRepository.findFailedWithRemainingRetries(
                        maxRetryAttempts, null);

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
        final String email = entry.getEmail();

        if (entry.getRawToken() == null) {
            log.error("Auth email outbox entry has null rawToken — " +
                            "skipping: entryId={}, email={}, type={}, status={}",
                    entry.getId(), email, entry.getEmailType(), entry.getStatus());
            entry.markPermanentlyFailed("rawToken is null — cannot send email");
            outboxRepository.save(entry);
            return;
        }

        try {
            // Route to correct email template based on type
            switch (entry.getEmailType()) {
                case INVITE ->
                        emailService.sendInviteEmail(email, entry.getRawToken());
                case PASSWORD_RESET ->
                        emailService.sendPasswordResetEmail(email, entry.getRawToken());
            }

            entry.markSent();
            outboxRepository.save(entry);

            log.info("Auth email sent: type={}, email={}, userId={}, attempt={}",
                    entry.getEmailType(), email,
                    entry.getUser().getId(), entry.getRetryCount() + 1);

        } catch (InviteEmailException e) {
            final int newRetryCount = entry.getRetryCount() + 1;

            if (newRetryCount >= maxRetryAttempts) {
                entry.markPermanentlyFailed(e.getMessage());
                outboxRepository.save(entry);
                log.error("Auth email permanently failed after {} attempts: " +
                                "type={}, email={}, userId={}, error={}",
                        maxRetryAttempts, entry.getEmailType(),
                        email, entry.getUser().getId(), e.getMessage());
            } else {
                entry.markFailed(e.getMessage());
                outboxRepository.save(entry);
                log.warn("Auth email failed (attempt {}/{}), will retry: " +
                                "type={}, email={}, error={}",
                        newRetryCount, maxRetryAttempts,
                        entry.getEmailType(), email, e.getMessage());
            }
        } catch (Exception e) {
            log.error("Unexpected error processing auth email outbox entry: " +
                            "entryId={}, type={}, email={}, error={}",
                    entry.getId(), entry.getEmailType(), email, e.getMessage(), e);
            entry.markFailed("Unexpected error: " + e.getMessage());
            outboxRepository.save(entry);
        }
    }
}