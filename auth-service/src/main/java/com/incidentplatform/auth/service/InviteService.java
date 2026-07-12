package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.AcceptInviteRequest;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles the accept-invite flow: validates the token, sets the user's
 * password, and marks the token as used — all in a single transaction.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>Admin calls {@code POST /api/v1/users} → user created, invite token generated</li>
 *   <li>Admin shares raw token with invited user (via Slack/email until email sending is ready)</li>
 *   <li>User calls {@code POST /api/v1/auth/accept-invite} with token + new password</li>
 *   <li>Token is validated (non-expired, non-used, type=INVITE)</li>
 *   <li>Password is BCrypt-hashed and set on the user</li>
 *   <li>Token is marked as used (single-use)</li>
 *   <li>User can now call {@code POST /api/v1/auth/login}</li>
 * </ol>
 *
 * <h2>Transaction boundary</h2>
 * Token consumption and password update are in a single {@code @Transactional}:
 * if the password update fails, the token is not consumed — the user can retry.
 * If the token is consumed but the password update fails (e.g. DB error),
 * the transaction rolls back and the token remains valid.
 */
@Service
public class InviteService {

    private static final Logger log = LoggerFactory.getLogger(InviteService.class);

    private final AuthTokenService authTokenService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditEventPublisher auditEventPublisher;

    public InviteService(AuthTokenService authTokenService,
                         UserRepository userRepository,
                         PasswordEncoder passwordEncoder,
                         AuditEventPublisher auditEventPublisher) {
        this.authTokenService = authTokenService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditEventPublisher = auditEventPublisher;
    }

    @Transactional
    public void acceptInvite(AcceptInviteRequest request) {
        // Validate token — throws 401 if invalid, expired, or already used
        final AuthToken token = authTokenService
                .consumeToken(request.token(), AuthToken.Type.INVITE);

        final User user = token.getUser();

        final String hash = passwordEncoder.encode(request.password());
        user.setPasswordHash(hash);
        userRepository.save(user);

        auditEventPublisher.publishAuth(
                user.getId(), token.getTenantId(),
                AuditEventTypes.USER_INVITE_ACCEPTED,
                "auth-service",
                user.getId().toString(),
                "Invite accepted — password set",
                java.util.Map.of());

        log.info("Invite accepted — password set: userId={}, tenant={}",
                user.getId(), user.getTenantId());
    }
}