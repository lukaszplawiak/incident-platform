package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthEmailStatus;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Handles the resend-invite flow — queues a new invite email for a user who
 * has not yet accepted their invitation.
 *
 * <h2>When resend is needed</h2>
 * <ul>
 *   <li>The original invite email was never delivered (PERMANENTLY_FAILED:
 *       SMTP kept failing until its 7-day deadline, backlog #0-52)</li>
 *   <li>The original invite link expired (7-day TTL)</li>
 *   <li>The user lost the email and needs a fresh link</li>
 * </ul>
 *
 * <h2>What resend does</h2>
 * <ol>
 *   <li>Validates that the user exists in this tenant and has not yet
 *       set a password (invite still pending)</li>
 *   <li>Queues a new invite request through {@link AuthEmailRequestService}
 *       — nothing else. {@code AuthEmailScheduler} sends it within 30
 *       seconds; when it does, it invalidates the user's earlier invite
 *       tokens and creates a fresh one (7 days from sending), and marks an
 *       older request still being retried SUPERSEDED (backlog #0-52)</li>
 * </ol>
 *
 * <h2>Idempotency guard</h2>
 * If the user already has a PENDING outbox entry (email not yet dispatched),
 * resend is rejected with 409 — there is no point creating a duplicate.
 * The admin should wait for the scheduler to process the existing entry.
 * A FAILED entry (still being retried, backlog #0-52) does not block: the
 * admin asked for a fresh link now, and the scheduler supersedes the older
 * request itself. This service never changes an existing outbox row, so it
 * cannot contend with the scheduler over one (before #0-52 it closed the
 * FAILED row itself, and a concurrent scheduler write turned that into a 409
 * or a unique-index 500).
 */
@Service
public class ResendInviteService {

    private static final Logger log =
            LoggerFactory.getLogger(ResendInviteService.class);

    private final UserRepository userRepository;
    private final AuthEmailOutboxRepository outboxRepository;
    private final AuthEmailRequestService emailRequests;
    private final AuditEventPublisher auditEventPublisher;

    public ResendInviteService(UserRepository userRepository,
                               AuthEmailOutboxRepository outboxRepository,
                               AuthEmailRequestService emailRequests,
                               AuditEventPublisher auditEventPublisher) {
        this.userRepository = userRepository;
        this.outboxRepository = outboxRepository;
        this.emailRequests = emailRequests;
        this.auditEventPublisher = auditEventPublisher;
    }

    /**
     * Resends an invite email to the specified user.
     *
     * @param userId the UUID of the user to re-invite
     * @throws ResourceNotFoundException 404 if user not found in this tenant
     * @throws BusinessException         409 if invite already accepted
     *                                   (user already has a password)
     * @throws BusinessException         409 if a PENDING outbox entry already
     *                                   exists (email dispatch in progress)
     */
    @Transactional
    public void resendInvite(UUID userId) {
        final String tenantId = TenantContext.get();

        final User user = userRepository
                .findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User", userId.toString()));

        // Guard: cannot resend to a user who already accepted the invite
        if (user.getPasswordHash() != null) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "Cannot resend invite — user has already accepted " +
                            "the original invitation and set a password",
                    HttpStatus.CONFLICT);
        }

        // Guard: no point resending if there's already a PENDING entry
        // (scheduler will send it within 30 seconds)
        outboxRepository.findFirstByUserIdAndEmailTypeOrderByCreatedAtDesc(userId, AuthEmailType.INVITE).ifPresent(latest -> {
            if (latest.getStatus() == AuthEmailStatus.PENDING) {
                throw new BusinessException(
                        ErrorCodes.BUSINESS_RULE_VIOLATION,
                        "An invite email is already queued for dispatch — " +
                                "please wait up to 30 seconds before retrying",
                        HttpStatus.CONFLICT);
            }
        });

        // Queue the new invite; see the class Javadoc for what the scheduler
        // does with earlier tokens and requests.
        emailRequests.requestInvite(user);

        auditEventPublisher.publishAuth(
                user.getId(), TenantContext.get(),
                AuditEventTypes.USER_INVITE_RESENT,
                "auth-service",
                user.getId().toString(),
                "Invite email resent",
                java.util.Map.of("email", user.getEmail()));

        log.info("Invite resend queued: userId={}, email={}, tenant={}",
                userId, user.getEmail(), tenantId);
    }
}