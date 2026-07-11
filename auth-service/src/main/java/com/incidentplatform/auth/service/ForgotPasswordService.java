package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.domain.AuthEmailStatus;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.auth.service.AuthTokenService.InviteTokenResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Initiates the self-service password recovery flow.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>User submits their email to {@code POST /api/v1/auth/forgot-password}</li>
 *   <li>This service looks up the user — if not found, returns silently</li>
 *   <li>If found, generates a PASSWORD_RESET token (15-minute TTL) and
 *       writes a PENDING outbox entry</li>
 *   <li>{@code AuthEmailScheduler} picks up the entry and sends the email</li>
 *   <li>User clicks the link and submits new password to
 *       {@code POST /api/v1/auth/reset-password}</li>
 * </ol>
 *
 * <h2>User Enumeration Protection — Layer 1: uniform HTTP response</h2>
 * {@link #initiateReset} always returns without throwing, regardless of
 * whether the email exists in the system. The HTTP endpoint returns
 * {@code 202 Accepted} in both cases. This prevents attackers from
 * probing which email addresses have accounts by comparing responses.
 *
 * <h2>User Enumeration Protection — Layer 2: timing attack (TODO)</h2>
 * When the user does not exist, this method returns after a fast DB lookup
 * (~2ms). When the user exists, it writes a token and outbox entry (~10ms).
 * An attacker measuring response times at scale could statistically
 * distinguish the two paths. {@code simulateWork()} should be added to the
 * non-existent path to equalise timing.
 *
 * @see <a href="https://owasp.org/www-project-web-security-testing-guide/">
 *      OWASP Testing Guide — User Enumeration</a>
 */
@Service
public class ForgotPasswordService {

    private static final Logger log =
            LoggerFactory.getLogger(ForgotPasswordService.class);

    private final UserRepository userRepository;
    private final AuthTokenService authTokenService;
    private final AuthEmailOutboxRepository outboxRepository;

    public ForgotPasswordService(UserRepository userRepository,
                                 AuthTokenService authTokenService,
                                 AuthEmailOutboxRepository outboxRepository) {
        this.userRepository   = userRepository;
        this.authTokenService = authTokenService;
        this.outboxRepository = outboxRepository;
    }

    /**
     * Initiates a password reset for the given email address.
     *
     * <p>Returns silently (no exception) whether or not the email exists —
     * this is intentional user enumeration protection. The caller (HTTP
     * endpoint) always returns {@code 202 Accepted}.
     *
     * <p>If a reset email is already pending for this user, the request is
     * silently ignored — the existing email will be dispatched within 30
     * seconds. This prevents abuse by submitting multiple requests to force
     * the token to change.
     *
     * @param email    the email address of the user requesting a reset
     * @param tenantId the tenant in which to look up the user
     */
    @Transactional
    public void initiateReset(String email, String tenantId) {
        final Optional<User> userOpt =
                userRepository.findByEmailAndTenantId(
                        email, tenantId);

        if (userOpt.isEmpty()) {
            // User enumeration protection — do NOT reveal that the email
            // has no account. Log at DEBUG only (not INFO) so the email
            // address does not appear in centralised log aggregators.
            //
            // TODO (backlog): add simulateWork() here to equalise response
            // timing between existing and non-existing users (timing attack
            // mitigation — user enumeration protection layer 2).
            log.debug("Password reset requested for unknown email — no-op");
            return;
        }

        final User user = userOpt.get();

        // Guard: if a reset email is already PENDING, silently ignore.
        // The existing entry will be dispatched within 30 seconds.
        // This prevents abuse by repeated rapid submissions.
        final boolean alreadyPending = outboxRepository
                .findLatestByUserIdAndType(user.getId(), AuthEmailType.PASSWORD_RESET)
                .filter(e -> e.getStatus() == AuthEmailStatus.PENDING)
                .isPresent();

        if (alreadyPending) {
            log.debug("Password reset already pending for userId={} — no-op",
                    user.getId());
            return;
        }

        // Generate token — 15-minute TTL (AuthTokenService.RESET_TTL_MINUTES)
        final InviteTokenResult tokenResult =
                authTokenService.generatePasswordResetTokenWithEntity(user, tenantId);

        // Write outbox entry — AuthEmailScheduler sends the email within 30s
        final AuthEmailOutbox outboxEntry = AuthEmailOutbox.passwordResetPending(
                user, tokenResult.token(), tokenResult.rawToken());
        outboxRepository.save(outboxEntry);

        // Log without the email address — prevents email enumeration via logs
        log.info("Password reset queued: userId={}, tenant={}",
                user.getId(), tenantId);
    }
}