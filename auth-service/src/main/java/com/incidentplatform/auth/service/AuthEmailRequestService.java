package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Records the intent to send an invite or password-reset email (backlog #0-52),
 * or a security notification about an MFA change (backlog #0-83).
 *
 * <p>The only way request paths ({@code UserService}, {@code ResendInviteService},
 * {@code ForgotPasswordService}, {@code MfaService}) put anything into the auth
 * email outbox, and all they do is INSERT: no token is created here and no existing row is
 * touched. {@code AuthEmailScheduler} creates the token when it sends the
 * email, and marks an older request SUPERSEDED when a newer one for the same
 * user and type exists. A request therefore never contends with the scheduler
 * over a row — the cause of the 409 / 500 answers resend-invite and
 * forgot-password could give while a FAILED entry was being retried.
 */
@Service
public class AuthEmailRequestService {

    /** The shortest time a security notification is retried. */
    static final Duration MIN_SECURITY_NOTIFICATION_DEADLINE = Duration.ofHours(24);

    private final AuthEmailOutboxRepository outboxRepository;
    private final Duration securityNotificationDeadline;

    /**
     * @param enrolmentGrace the platform API's grace period for a new factor;
     *                       a security notification is retried at least that
     *                       long (and at least 24 h), so a notice is not given
     *                       up while its factor could still be waiting for it
     *                       (found in review: the deadline was a fixed 24 h)
     */
    public AuthEmailRequestService(AuthEmailOutboxRepository outboxRepository,
                                   @Value("${platform.mfa.enrolment-grace:PT24H}") Duration enrolmentGrace) {
        this.outboxRepository = outboxRepository;
        this.securityNotificationDeadline = enrolmentGrace.compareTo(MIN_SECURITY_NOTIFICATION_DEADLINE) > 0
                ? enrolmentGrace : MIN_SECURITY_NOTIFICATION_DEADLINE;
    }

    /** Queues an invite email; part of the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthEmailOutbox requestInvite(User user) {
        return request(user, AuthEmailType.INVITE);
    }

    /** Queues a password-reset email; part of the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthEmailOutbox requestPasswordReset(User user) {
        return request(user, AuthEmailType.PASSWORD_RESET);
    }

    /**
     * Queues a security notification that MFA was enabled or disabled on the
     * user's account (backlog #0-83); part of the caller's transaction, so it
     * is sent only if the change commits. Retried until the larger of 24 h
     * and the platform API's grace period for a new factor.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthEmailOutbox requestMfaChangeNotification(User user, boolean enabled) {
        return outboxRepository.save(AuthEmailOutbox.request(
                user, enabled ? AuthEmailType.MFA_ENABLED : AuthEmailType.MFA_DISABLED,
                securityNotificationDeadline));
    }

    /**
     * Queues the notice that an administrator reset the user's MFA (backlog
     * #0-88); part of the caller's transaction, same deadline as the other
     * security notifications.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthEmailOutbox requestMfaResetNotification(User user) {
        return outboxRepository.save(AuthEmailOutbox.request(
                user, AuthEmailType.MFA_RESET, securityNotificationDeadline));
    }

    private AuthEmailOutbox request(User user, AuthEmailType type) {
        return outboxRepository.save(AuthEmailOutbox.request(
                user, type, AuthTokenService.emailTokenLifetime(type.tokenType())));
    }
}
