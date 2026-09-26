package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.domain.UserRole;
import com.incidentplatform.auth.dto.CreateUserRequest;
import com.incidentplatform.auth.dto.CreateUserResponse;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final AuthEmailRequestService emailRequests;
    private final AuditEventPublisher auditEventPublisher;

    public UserService(UserRepository userRepository,
                       AuthEmailRequestService emailRequests,
                       AuditEventPublisher auditEventPublisher) {
        this.userRepository = userRepository;
        this.emailRequests = emailRequests;
        this.auditEventPublisher = auditEventPublisher;
    }

    /**
     * Creates a new user and schedules an invite email via the Outbox Pattern.
     *
     * <h2>What happens in this transaction</h2>
     * <ol>
     *   <li>Duplicate email guard — 409 if email already exists in tenant</li>
     *   <li>INSERT user (no password — set later via accept-invite)</li>
     *   <li>INSERT auth_email_outbox (PENDING invite request) — no token yet</li>
     *   <li>COMMIT — both records written atomically</li>
     * </ol>
     *
     * <h2>What does NOT happen here</h2>
     * No email is sent and no token is created in this method.
     * {@code AuthEmailScheduler} picks up the PENDING request (typically
     * within 30 seconds), creates the invite token — only its SHA-256 hash is
     * stored — and sends the email with the link (backlog #0-52).
     *
     * <h2>Response</h2>
     * The response no longer contains {@code inviteToken} — the token goes
     * directly to the user's inbox, never through the admin's HTTP client.
     *
     * @throws BusinessException 409 if the email already exists in this tenant
     */
    @Transactional
    public CreateUserResponse createUser(CreateUserRequest request) {
        final String tenantId = TenantContext.get();

        // Guard: prevent duplicate email within tenant
        userRepository.findByEmailAndTenantId(
                        request.email(), tenantId)
                .ifPresent(existing -> {
                    log.warn("User creation failed — email already exists: " +
                            "email={}, tenant={}", request.email(), tenantId);
                    throw new BusinessException(
                            ErrorCodes.EMAIL_ALREADY_EXISTS,
                            String.format(
                                    "A user with email '%s' already exists " +
                                            "in this tenant", request.email()),
                            HttpStatus.CONFLICT);
                });

        // Create user — no password yet (set via accept-invite)
        final User user = User.register(tenantId, request.email());

        // Assign roles
        for (final String role : request.roles()) {
            user.getRoles().add(UserRole.grant(user, tenantId, role));
        }

        userRepository.save(user);

        auditEventPublisher.publishAuth(
                user.getId(), tenantId,
                AuditEventTypes.USER_CREATED,
                "auth-service",
                "auth-service",
                "User created",
                java.util.Map.of("email", user.getEmail(),
                        "roles", request.roles()));

        log.info("User created: userId={}, email={}, tenant={}, roles={}",
                user.getId(), user.getEmail(), tenantId, request.roles());

        // Queue the invite email; AuthEmailScheduler creates the token when it
        // sends it (backlog #0-52), so no credential is created or stored here.
        emailRequests.requestInvite(user);

        auditEventPublisher.publishAuth(
                user.getId(), tenantId,
                AuditEventTypes.USER_INVITE_SENT,
                "auth-service",
                "auth-service",
                "Invite email queued",
                java.util.Map.of("email", user.getEmail()));

        log.info("Invite email queued: userId={}, email={}, tenant={}",
                user.getId(), user.getEmail(), tenantId);

        // Return response WITHOUT the token — it goes directly to user's inbox.
        return new CreateUserResponse(
                user.getId(),
                tenantId,
                user.getEmail(),
                user.getRoleNames(),
                user.isActive(),
                user.getCreatedAt()
        );
    }
}