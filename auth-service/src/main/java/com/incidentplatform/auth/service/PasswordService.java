package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.dto.ChangePasswordRequest;
import com.incidentplatform.auth.dto.ResetPasswordRequest;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.auth.service.AuthTokenService;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles self-service password change for authenticated users.
 *
 * <h2>Security design</h2>
 * Current password verification is mandatory — even if the user has a valid
 * JWT, they must prove knowledge of the current password before changing it.
 * This protects against session hijacking: an attacker with a stolen token
 * cannot change the password without knowing the current one.
 *
 * <h2>Error messages</h2>
 * Wrong current password returns 401 "Invalid credentials" — the same message
 * used by the login endpoint. This prevents an attacker from using this
 * endpoint to verify whether a guessed password is correct.
 */
@Service
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    private final UserRepository userRepository;
    private final AuthTokenService authTokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuditEventPublisher auditEventPublisher;
    private final ApiKeyService apiKeyService;

    public PasswordService(UserRepository userRepository,
                           AuthTokenService authTokenService,
                           PasswordEncoder passwordEncoder,
                           AuditEventPublisher auditEventPublisher,
                           ApiKeyService apiKeyService) {
        this.userRepository  = userRepository;
        this.authTokenService = authTokenService;
        this.passwordEncoder = passwordEncoder;
        this.auditEventPublisher = auditEventPublisher;
        this.apiKeyService = apiKeyService;
    }


    /**
     * Completes the self-service password recovery flow.
     *
     * <ol>
     *   <li>Validates and consumes the reset token (single-use, 15-minute TTL)</li>
     *   <li>Sets the new password (BCrypt)</li>
     *   <li>Invalidates all refresh tokens — forces re-login on all devices.
     *       This ensures that if an attacker had active sessions via a
     *       compromised account, they are terminated immediately.</li>
     *   <li>Backlog #0-83: invalidates unfinished logins (MFA session and MFA
     *       setup tokens) and discards an MFA setup begun but not enabled.</li>
     *   <li>Backlog #0-89: revokes the user's personal API keys. A reset is
     *       how an owner recovers an account whose password someone else
     *       may know (OWASP: recovery of a compromised account ends every
     *       session and credential), and a key that person created would
     *       otherwise keep the owner's roles in auth-service. Always, unlike
     *       {@link #changePassword}, where the caller chooses.</li>
     * </ol>
     *
     * <h2>Never touches the second factor (backlog #0-88)</h2>
     * #0-83 made a reset remove a factor still within the grace period, the
     * only remedy then for a factor someone else enrolled with the owner's
     * password. That let a mailbox alone undo a fresh factor, which mature
     * systems never allow (NIST SP 800-63B: recovery must not lower the
     * assurance level). A reset now keeps MFA; an admin of the tenant resets
     * it instead ({@link MfaService#resetMfaByAdmin}).
     *
     * @param request token + new password
     * @throws com.incidentplatform.shared.exception.BusinessException
     *         401 if token is invalid, expired, or already used
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request, String tenantId) {
        // consumeToken validates, marks used atomically — throws 401 if invalid
        final AuthToken token = authTokenService.consumeToken(
                request.token(), AuthToken.Type.PASSWORD_RESET);

        final com.incidentplatform.auth.domain.User user = token.getUser();

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        // Backlog #0-83: an MFA setup begun but not enabled goes with the old
        // password too (found in review); hygiene, since nobody can finish it
        // without a live session after the reset.
        user.discardPendingMfaSecret();
        userRepository.save(user);

        // Backlog #0-83: a half-finished login of whoever had the old password
        // (an MFA session or MFA setup token) must not survive the reset either.
        authTokenService.invalidateLoginContinuationTokens(user.getId());

        // Invalidate all refresh tokens — terminates all active sessions.
        // An attacker who had access to the account is now logged out.
        authTokenService.invalidateAllRefreshTokens(user.getId());

        // Backlog #0-89: last, as the bulk update detaches the user.
        final int keysRevoked = apiKeyService.revokeAllPersonalKeysForUser(
                user.getId(), token.getTenantId());

        auditEventPublisher.publishAuth(
                user.getId(), token.getTenantId(),
                AuditEventTypes.USER_PASSWORD_RESET,
                "auth-service",
                user.getId().toString(),
                "Password reset via email token",
                java.util.Map.of(ApiKeyService.AUDIT_PERSONAL_KEYS_REVOKED, String.valueOf(keysRevoked)));

        log.info("Password reset completed: userId={}, tenant={}",
                user.getId(), token.getTenantId());
    }

    @Transactional
    public void changePassword(UserPrincipal principal,
                               ChangePasswordRequest request) {

        final User user = userRepository
                .findByIdAndTenantId(principal.userId(), principal.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User", principal.userId()));

        // Verify current password before allowing the change.
        // Guards against session hijacking — attacker with stolen token
        // cannot change the password without knowing the current one.
        if (!passwordEncoder.matches(
                request.currentPassword(), user.getPasswordHash())) {
            log.warn("Password change failed — wrong current password: " +
                    "userId={}, tenant={}", principal.userId(), principal.tenantId());
            throw new BusinessException(
                    ErrorCodes.UNAUTHORIZED,
                    "Invalid credentials",
                    HttpStatus.UNAUTHORIZED);
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        // Fixed: previously did nothing here at all — an attacker with a
        // stolen refresh token (but not the current password) would keep
        // a working session even after the legitimate user proactively
        // changed their password in response to suspecting compromise,
        // undermining the whole point of allowing that defensive action.
        // resetPassword() above already invalidates sessions on a
        // password change; this brings changePassword() in line with it,
        // but more precisely — leaves the session actively in use right
        // now (the caller just proved their current password) alone,
        // rather than forcing them to immediately re-log-in on their own
        // device too. See AuthToken.sessionId's own Javadoc (migration
        // V16) for the full account of what makes this precision possible.
        authTokenService.invalidateAllRefreshTokensExceptSession(
                principal.userId(), principal.sessionId());

        // Backlog #0-89: only when asked (OWASP ASVS 3.3.3 "gives the
        // option"). A routine change keeps the keys; one made because the
        // password may be known to someone else ends them too.
        final int keysRevoked = request.revokesPersonalApiKeys()
                ? apiKeyService.revokeAllPersonalKeysForUser(principal.userId(), principal.tenantId())
                : 0;

        auditEventPublisher.publishAuth(
                principal.userId(), principal.tenantId(),
                AuditEventTypes.USER_PASSWORD_CHANGED,
                "auth-service",
                principal.userId().toString(),
                "Password changed",
                java.util.Map.of(ApiKeyService.AUDIT_PERSONAL_KEYS_REVOKED, String.valueOf(keysRevoked)));

        log.info("Password changed: userId={}, tenant={}",
                principal.userId(), principal.tenantId());
    }
}