package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.MfaBackupCode;
import com.incidentplatform.auth.domain.Role;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.LoginResponse;
import com.incidentplatform.auth.dto.MfaBackupCodesStatusResponse;
import com.incidentplatform.auth.dto.MfaEnableResponse;
import com.incidentplatform.auth.dto.MfaEnableWithLoginResponse;
import com.incidentplatform.auth.dto.MfaSetupResponse;
import com.incidentplatform.auth.ratelimit.BruteForceProtectionService;
import com.incidentplatform.auth.ratelimit.MfaResetRateLimiter;
import com.incidentplatform.auth.ratelimit.RateLimitDecision;
import com.incidentplatform.auth.ratelimit.RateLimitRefusedException;
import com.incidentplatform.auth.repository.MfaBackupCodeRepository;
import com.incidentplatform.auth.repository.TeamMemberRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.ReservedTenants;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    /** Break-glass inputs, bounded so they fit the audit trail's columns (backlog #0-88). */
    static final int BREAK_GLASS_ACTOR_MAX = 100;
    static final int BREAK_GLASS_REASON_MAX = 500;
    /** {@code user@host} of the break-glass process; the runner cuts it to fit. */
    public static final int BREAK_GLASS_EXECUTED_ON_MAX = 200;

    /** Audit metadata of an admin reset that also revoked the keys the user created (backlog #0-89). */
    static final String AUDIT_KEYS_CREATED_SINCE = "revokeKeysCreatedSince";
    static final String AUDIT_CREATED_KEYS_REVOKED = "createdApiKeysRevoked";

    private final UserRepository userRepository;
    private final MfaBackupCodeRepository backupCodeRepository;
    private final AuthTokenService authTokenService;
    private final TeamMemberRepository teamMemberRepository;
    private final TotpService totpService;
    private final AesEncryptionService aesEncryptionService;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();
    private final JwtUtils jwtUtils;
    private final AuditEventPublisher auditEventPublisher;
    private final BruteForceProtectionService bruteForceProtectionService;
    private final AuthEmailRequestService authEmailRequestService;
    private final MfaSessionStatusService mfaSessionStatusService;
    private final MfaResetRateLimiter mfaResetRateLimiter;
    private final ApiKeyService apiKeyService;
    private final TransactionTemplate transaction;

    public MfaService(UserRepository userRepository,
                      MfaBackupCodeRepository backupCodeRepository,
                      AuthTokenService authTokenService,
                      TeamMemberRepository teamMemberRepository,
                      TotpService totpService,
                      @Qualifier("mfaEncryptionService") AesEncryptionService aesEncryptionService,
                      PasswordEncoder passwordEncoder,
                      JwtUtils jwtUtils,
                      AuditEventPublisher auditEventPublisher,
                      BruteForceProtectionService bruteForceProtectionService,
                      AuthEmailRequestService authEmailRequestService,
                      MfaSessionStatusService mfaSessionStatusService,
                      MfaResetRateLimiter mfaResetRateLimiter,
                      ApiKeyService apiKeyService,
                      PlatformTransactionManager transactionManager) {
        this.userRepository       = userRepository;
        this.backupCodeRepository = backupCodeRepository;
        this.authTokenService     = authTokenService;
        this.teamMemberRepository = teamMemberRepository;
        this.totpService          = totpService;
        this.aesEncryptionService = aesEncryptionService;
        this.passwordEncoder      = passwordEncoder;
        this.jwtUtils             = jwtUtils;
        this.auditEventPublisher  = auditEventPublisher;
        this.bruteForceProtectionService = bruteForceProtectionService;
        this.authEmailRequestService = authEmailRequestService;
        this.mfaSessionStatusService = mfaSessionStatusService;
        this.mfaResetRateLimiter = mfaResetRateLimiter;
        this.apiKeyService = apiKeyService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    // ── Setup (step 1) ────────────────────────────────────────────────────

    /**
     * Generates a new TOTP secret and stores it as pending.
     *
     * <p>The secret is generated fresh on every call — if setup is restarted,
     * the previous pending secret is overwritten. The plain secret is returned
     * once for QR display; only the AES-encrypted form is stored.
     */
    @Transactional
    public MfaSetupResponse setupMfa(UserPrincipal principal) {
        requireLiveSession(principal);
        final String tenantId = TenantContext.get();
        final User user = requireUser(principal.userId(), tenantId);

        final MfaSetupResponse response = doSetupMfa(user, tenantId);

        log.info("MFA setup initiated: userId={}, tenant={}", principal.userId(), tenantId);

        return response;
    }

    // ── Enable (step 2) ───────────────────────────────────────────────────

    /**
     * Activates MFA after the user confirms the TOTP code from their app.
     *
     * @return backup codes (plain) — shown once, stored as Argon2 hashes
     */
    @Transactional
    public MfaEnableResponse enableMfa(String totpCode, UserPrincipal principal) {
        requireLiveSession(principal);
        final String tenantId = TenantContext.get();
        final User user = requireUser(principal.userId(), tenantId);

        final List<String> plainCodes = doEnableMfa(
                user, tenantId, totpCode,
                "No pending MFA setup found. Call POST /auth/mfa/setup first.",
                "MFA enabled");

        log.info("MFA enabled: userId={}, tenant={}", principal.userId(), tenantId);

        return MfaEnableResponse.of(plainCodes);
    }

    // ── Disable ───────────────────────────────────────────────────────────

    /**
     * Disables MFA after verifying both password and TOTP code.
     * Requires both factors to prevent a stolen session from disabling MFA.
     */
    @Transactional
    public void disableMfa(String password, String totpCode, UserPrincipal principal) {
        final String tenantId = TenantContext.get();
        final User user = requireUser(principal.userId(), tenantId);

        if (!user.isMfaEnabled()) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "MFA is not enabled",
                    HttpStatus.CONFLICT);
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(
                    ErrorCodes.UNAUTHORIZED,
                    "Invalid credentials",
                    HttpStatus.UNAUTHORIZED);
        }

        final String plainSecret = aesEncryptionService.decrypt(user.getMfaSecret());
        if (!verifyTotpAndRecordUsage(user, plainSecret, totpCode)) {
            throw new BusinessException(
                    ErrorCodes.UNAUTHORIZED,
                    "Invalid TOTP code",
                    HttpStatus.UNAUTHORIZED);
        }

        clearFactor(user, tenantId);
        authEmailRequestService.requestMfaChangeNotification(user, false);

        auditEventPublisher.publishAuth(
                principal.userId(), tenantId,
                AuditEventTypes.MFA_DISABLED,
                "auth-service",
                principal.userId().toString(),
                "MFA disabled",
                Map.of());

        log.info("MFA disabled: userId={}, tenant={}", principal.userId(), tenantId);
    }

    // ── Reset by an admin ────────────────────────────────────────────────

    /**
     * An admin of the user's tenant removes the user's second factor
     * (backlog #0-88): the factor, a pending setup, the backup codes, the
     * sessions' MFA marks and every session and unfinished login of the
     * user. The user is emailed (MFA_RESET through the auth email outbox, its
     * own text so a reset they did not ask for stands out) and logs in with
     * their password alone, to enrol a factor again.
     *
     * <h2>Why an admin, not a password reset</h2>
     * The help a user needs when their phone is lost, or when someone else
     * enrolled a factor with their password. Until #0-88 a password reset by
     * email removed a factor younger than the grace period (#0-83), so a
     * mailbox alone could undo a fresh factor; mature systems never allow
     * that (NIST SP 800-63B: recovery must not lower the assurance level),
     * and the B2B pattern is an admin reset (Okta "Reset Multifactor", Entra
     * ID "Require re-register MFA"). A password reset now never touches MFA.
     *
     * <h2>Rules</h2>
     * <ul>
     *   <li>Not on one's own account: {@code /mfa/disable} is for that, and
     *       needs the factor.</li>
     *   <li>The admin's own session must pass the platform API's MFA rule
     *       ({@link MfaSessionStatusService#check}): MFA completed within the
     *       maximum session age (12 h), with a factor whose MFA_ENABLED notice
     *       went out at least the grace period (24 h) ago. Found in review:
     *       with only "completed MFA", a password thief could enrol a factor
     *       and reset every user of the tenant at once, and a refresh chain
     *       (30 days, carrying its original MFA time) could do it weeks after
     *       the MFA login. An API key has no session and never passes.</li>
     *   <li>Any other user of the tenant that is not archived, admins
     *       included, so a second admin can help an admin, and an operator
     *       admin another operator admin. A deactivated user too (found in
     *       review, kept on purpose): resetting a stranger's factor before
     *       reactivating the account leaves no moment in which the stranger
     *       could log in with it; the reset opens nothing, as a deactivated
     *       user cannot log in.</li>
     *   <li>Rate-limited per admin and per tenant, fail-closed
     *       ({@link MfaResetRateLimiter}), counted only for a reset that is
     *       about to happen (step-up passed, user found, factor present), so
     *       refused attempts cannot use up a tenant's budget.</li>
     *   <li>Every session ends, because the usual reason is a compromised
     *       account: if the password is known to someone else, the user
     *       resets it first, otherwise its holder could log in and enrol a
     *       factor of their own again. Access tokens already issued live out
     *       their 15 minutes, as after a password reset; the platform API
     *       stops accepting them at once (no live session).</li>
     *   <li>The user's personal API keys are revoked (backlog #0-89), for
     *       the same reason: one created with the stolen password would
     *       otherwise keep working after the recovery.</li>
     * </ul>
     *
     * @throws BusinessException 403 on one's own account or when the admin's session
     *                           fails the MFA rule (the message names the condition),
     *                           409 if the user has no factor
     * @throws RateLimitRefusedException when a limit refuses (429) or cannot be checked (503)
     * @throws ResourceNotFoundException if the user is not in the tenant, or is archived
     */
    @Transactional
    public void resetMfaByAdmin(UUID targetUserId, UserPrincipal admin) {
        resetMfaByAdmin(targetUserId, admin, null);
    }

    /**
     * {@link #resetMfaByAdmin(UUID, UserPrincipal)}, and when
     * {@code revokeKeysCreatedSince} is given, also every API key the user
     * created at or after it (backlog #0-89): the tenant and integration keys
     * an intruder made with the account survive the reset otherwise, which
     * revokes only personal keys. Same step-up and limit as the reset, one
     * transaction, the count in the reset's audit event.
     */
    @Transactional
    public void resetMfaByAdmin(UUID targetUserId, UserPrincipal admin, Instant revokeKeysCreatedSince) {
        final String tenantId = TenantContext.get();

        if (targetUserId.equals(admin.userId())) {
            throw new BusinessException(
                    ErrorCodes.FORBIDDEN,
                    "You cannot reset your own MFA. Use POST /api/v1/auth/mfa/disable.",
                    HttpStatus.FORBIDDEN);
        }
        final MfaSessionStatusService.Status stepUp =
                mfaSessionStatusService.check(admin.userId(), tenantId, admin.sessionId());
        if (stepUp != MfaSessionStatusService.Status.ACCEPTED) {
            log.warn("MFA reset refused, admin session fails the MFA rule: adminId={}, tenant={}, status={}",
                    admin.userId(), tenantId, stepUp);
            throw new BusinessException(ErrorCodes.FORBIDDEN, stepUpRefusal(stepUp), HttpStatus.FORBIDDEN);
        }
        final User user = requireUser(targetUserId, tenantId);
        if (!user.isMfaEnabled()) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "MFA is not enabled",
                    HttpStatus.CONFLICT);
        }
        // Last check before the change, so only a reset that would happen
        // counts (review: a 404 or 409 used to spend the admin's budget).
        final RateLimitDecision limit = mfaResetRateLimiter.tryConsume(admin.userId(), tenantId);
        if (!limit.allowed()) {
            throw new RateLimitRefusedException(limit);
        }

        final int keysRevoked = resetFactorAndSessions(user, tenantId, false);
        final Map<String, Object> metadata = new java.util.HashMap<>(Map.of(
                "resetBy", admin.userId().toString(),
                ApiKeyService.AUDIT_PERSONAL_KEYS_REVOKED, String.valueOf(keysRevoked)));
        if (revokeKeysCreatedSince != null) {
            final var created = apiKeyService.revokeCreatedBy(
                    targetUserId, tenantId, revokeKeysCreatedSince, admin.userId());
            metadata.put(AUDIT_KEYS_CREATED_SINCE, revokeKeysCreatedSince.toString());
            metadata.put(AUDIT_CREATED_KEYS_REVOKED, String.valueOf(created.count()));
            // Review: the ids too, as the revoke-created-by endpoint records them.
            metadata.put("keyIds", ApiKeyService.joinedIds(created.revokedKeyIds()));
            metadata.put("integrationIds", ApiKeyService.joinedIds(created.revokedIntegrationIds()));
        }

        auditEventPublisher.publishAuth(
                targetUserId, tenantId,
                AuditEventTypes.MFA_RESET_BY_ADMIN,
                "auth-service",
                admin.userId().toString(),
                "MFA reset by an administrator",
                Map.copyOf(metadata));

        log.warn("MFA reset by an administrator: userId={}, tenant={}, by={}",
                targetUserId, tenantId, admin.userId());
    }

    /**
     * The break-glass form of {@link #resetMfaByAdmin} (backlog #0-88), for a
     * platform operator admin with no other operator admin to reset them. Run
     * from the command line by whoever operates the deployment
     * ({@code BreakGlassMfaResetRunner}), not over HTTP: there is no session
     * to step up from, so the trust is the same as for database access.
     *
     * <p>Does exactly what the admin reset does, with the same email to the
     * account (MFA_RESET), and is audited as {@code MFA_RESET_BREAK_GLASS} with the
     * operator's name and reason. The audit event is written to the outbox in
     * this transaction (backlog #0-84), so the reset and its event commit
     * together or not at all; the running auth-service's relay sends it. Until
     * #0-84 this waited for Kafka's acknowledgement inside the transaction
     * ({@code publishAuthConfirmed}, removed).
     *
     * <p>Limited to admins of the {@code platform-operator} tenant: a customer
     * tenant's admins reset each other, and the platform never acts inside a
     * tenant with an admin (#0-80), except for the delayed, announced recovery
     * of a customer tenant's only admin ({@code MfaRecoveryService}, #0-90).
     * The actor and reason go into the log and the audit trail, so control
     * characters (a forged log line) are refused.
     *
     * <p>The actor is the name the operator types, so the audit event also
     * records where the command ran ({@code executedOn}: the OS user and host
     * of the process, found in review), which the operator does not type.
     *
     * @param executedOn the OS user and host of the process, e.g. {@code appuser@3f2a9c}
     * @return the id of the user whose MFA was reset
     * @throws IllegalArgumentException if the actor, reason or executedOn is blank, too long or
     *                                  contains control characters
     * @throws ResourceNotFoundException if no operator user that is not archived has exactly
     *                                   this email (as stored: case-sensitive, like login)
     * @throws BusinessException 409 if the user is not an operator admin or has no factor
     */
    @Transactional
    public UUID resetMfaBreakGlass(String email, String actor, String reason, String executedOn) {
        requireText("actor", actor, BREAK_GLASS_ACTOR_MAX);
        requireText("reason", reason, BREAK_GLASS_REASON_MAX);
        requireText("executedOn", executedOn, BREAK_GLASS_EXECUTED_ON_MAX);
        final String tenantId = ReservedTenants.PLATFORM_OPERATOR;

        final User user = userRepository.findByEmailAndTenantId(email == null ? null : email.strip(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("No user in " + tenantId
                        + " with exactly this email (case-sensitive, as stored and as used to log in)"));
        if (!user.getRoleNames().contains(Role.ROLE_ADMIN.name())) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "Break-glass resets only an operator admin; another operator admin resets other users",
                    HttpStatus.CONFLICT);
        }
        if (!user.isMfaEnabled()) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "MFA is not enabled",
                    HttpStatus.CONFLICT);
        }

        final int keysRevoked = resetFactorAndSessions(user, tenantId, false);

        final String auditActor = "break-glass:" + actor.strip();
        auditEventPublisher.publishAuth(
                user.getId(), tenantId,
                AuditEventTypes.MFA_RESET_BREAK_GLASS,
                "auth-service",
                auditActor,
                "MFA reset by break-glass (no other operator admin)",
                Map.of("resetBy", auditActor, "reason", reason.strip(), "executedOn", executedOn.strip(),
                        ApiKeyService.AUDIT_PERSONAL_KEYS_REVOKED, String.valueOf(keysRevoked)));

        log.warn("MFA reset by break-glass: userId={}, tenant={}, by={}, executedOn={}",
                user.getId(), tenantId, auditActor, executedOn.strip());
        return user.getId();
    }

    private static void requireText(String name, String value, int max) {
        if (value == null || value.isBlank() || value.strip().length() > max
                || value.codePoints().anyMatch(MfaService::isUnsafeInLog)) {
            throw new IllegalArgumentException("break-glass " + name + " is required, at most " + max
                    + " characters, without control, line-separator or formatting characters");
        }
    }

    /**
     * Characters that could forge or disguise a log line or an audit entry:
     * control characters, the Unicode line and paragraph separators (U+2028,
     * U+2029) and formatting characters such as bidi overrides (U+202E);
     * found in review, as {@code isISOControl} alone misses the last two.
     */
    static boolean isUnsafeInLog(int codePoint) {
        final int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.FORMAT;
    }

    /** The 403 of an admin MFA reset, naming the failed condition like the platform API's. */
    static String stepUpRefusal(MfaSessionStatusService.Status status) {
        return switch (status) {
            case NO_MFA -> "Resetting another user's MFA requires a login that completed MFA: "
                    + "log in with your authenticator code.";
            case MFA_TOO_OLD -> "Resetting another user's MFA requires a recent MFA login: "
                    + "log in again with your authenticator code.";
            // One answer for both, as the platform API gives (backlog #0-83).
            case MFA_ENROLLED_TOO_RECENTLY, MFA_NOTICE_NOT_DELIVERED -> "Resetting another user's MFA "
                    + "requires a second factor you have had for some time (counted from the email "
                    + "announcing it). Ask another administrator, or try again later.";
            case ACCEPTED -> throw new IllegalStateException("an accepted session is not refused");
        };
    }

    // ── Verify TOTP ───────────────────────────────────────────────────────

    /**
     * Completes MFA login — verifies TOTP code and issues access + refresh tokens.
     *
     * <h2>Fixed (backlog #58): brute-force protection added</h2>
     * Previously had no failure limiting at all — see
     * {@link BruteForceProtectionService}'s own Javadoc for the full
     * account of why this was a genuine gap (an attacker who already has
     * a valid password could cycle through unlimited TOTP guesses over
     * time, one per login/MFA-session cycle) even though the sibling
     * login endpoint has always had this protection.
     *
     * <p>Uses {@link AuthTokenService#peekToken} to resolve the user
     * (and thus the lockout key) BEFORE consuming the token — checking
     * lockout first, the same ordering {@code AuthService.login} already
     * uses for the identical timing-attack reason, and avoids burning a
     * legitimate, unexpired session token on a request that's about to
     * be rejected for lockout anyway. {@link AuthTokenService#consumeToken}
     * only runs once we know the request should proceed.
     *
     * <p>A transaction of its own ({@link #transaction}), not
     * {@code @Transactional}: a wrong code rolls it back (the consumed
     * token with it, so the user can try again) and the refusal is audited
     * after it ended (see {@link #refuse}).
     */
    public LoginResponse verifyMfaToken(String rawMfaToken, String totpCode) {
        return completeOrRefuse(transaction.execute(status -> verifyTotpInTransaction(
                rawMfaToken, totpCode, status)));
    }

    private MfaAttempt verifyTotpInTransaction(String rawMfaToken, String totpCode,
                                               TransactionStatus status) {
        final MfaSessionContext session =
                resolveAndCheckMfaSession(rawMfaToken, "TOTP");

        final User user = session.token().getUser();

        final String plainSecret = aesEncryptionService.decrypt(user.getMfaSecret());

        if (!verifyTotpAndRecordUsage(user, plainSecret, totpCode)) {
            bruteForceProtectionService.recordFailure(
                    BruteForceProtectionService.Scope.MFA,
                    session.lockoutIdentifier(), session.tenantId());

            status.setRollbackOnly();
            return MfaAttempt.refused(user.getId(), session.tenantId(),
                    "MFA verification failed — invalid TOTP code", "Invalid TOTP code");
        }

        // Fixed (backlog #59): explicit save for the mfaLastUsedTimeStep
        // mutation verifyTotpAndRecordUsage just made — this method
        // previously only ever READ `user`, never wrote to it, so unlike
        // the other 3 TOTP-verifying call sites (which already save
        // `user` for their own, pre-existing reasons — enabling/disabling
        // MFA), there was nothing here relying on or establishing this
        // pattern before now. Not calling save() explicitly would likely
        // still work via Hibernate's dirty-checking (since `user` remains
        // a managed entity for this whole @Transactional method) — but
        // relying on that implicitly, for a field this security-relevant,
        // is exactly the kind of fragile-and-non-obvious behavior worth
        // avoiding; explicit and consistent with every other mutation
        // site in this class beats implicit and easy to break by a
        // future refactor of this method's transactional boundaries.
        userRepository.save(user);

        bruteForceProtectionService.recordSuccess(
                BruteForceProtectionService.Scope.MFA,
                session.lockoutIdentifier(), session.tenantId());

        return MfaAttempt.completed(issueTokens(user, session.tenantId()));
    }

    // ── Setup (forced flow — tenant requires MFA, no access token yet) ─────

    /**
     * Same as {@link #setupMfa(UserPrincipal)} but for the tenant-required-MFA
     * login flow (see AuthService.login()'s MFA_SETUP_REQUIRED branch),
     * where the user has no access token — identifies the user via the
     * setup token instead of the authenticated principal.
     *
     * <p>Uses {@link AuthTokenService#peekToken} rather than
     * {@link AuthTokenService#consumeToken} — this step may legitimately be
     * retried (QR didn't scan, user wants a fresh secret) before the final
     * {@link #enableMfaWithSetupToken} call actually consumes the token.
     */
    @Transactional
    public MfaSetupResponse setupMfaWithSetupToken(String rawSetupToken) {
        final AuthToken setupToken = authTokenService.peekToken(
                rawSetupToken, AuthToken.Type.MFA_SETUP_REQUIRED);

        final User user = setupToken.getUser();
        final String tenantId = setupToken.getTenantId();

        final MfaSetupResponse response = doSetupMfa(user, tenantId);

        log.info("MFA setup (tenant-required flow) initiated: userId={}, tenant={}",
                user.getId(), tenantId);

        return response;
    }

    /**
     * Same as {@link #enableMfa(String, UserPrincipal)} but for the
     * tenant-required-MFA login flow. Consumes the setup token (single-use,
     * unlike the setup step) and — since the whole point of this flow is
     * that login was blocked pending MFA configuration — completes login
     * by issuing real access/refresh tokens via the same
     * {@link #issueTokens} used by TOTP and backup-code verification.
     */
    @Transactional
    public MfaEnableWithLoginResponse enableMfaWithSetupToken(
            String rawSetupToken, String totpCode) {
        final AuthToken setupToken = authTokenService.consumeToken(
                rawSetupToken, AuthToken.Type.MFA_SETUP_REQUIRED);

        final User user = setupToken.getUser();
        final String tenantId = setupToken.getTenantId();

        final List<String> plainCodes = doEnableMfa(
                user, tenantId, totpCode,
                "No pending MFA setup found. Call POST /auth/mfa/setup-required first.",
                "MFA enabled (tenant-required flow, login completed)");

        log.info("MFA enabled via tenant-required flow, completing login: userId={}, tenant={}",
                user.getId(), tenantId);

        final LoginResponse loginResponse = issueTokens(user, tenantId);

        return new MfaEnableWithLoginResponse(plainCodes, loginResponse);
    }

    // ── Verify backup code ────────────────────────────────────────────────

    /**
     * Completes MFA login using a backup code instead of TOTP.
     *
     * <h2>Fixed (backlog #58): brute-force protection added</h2>
     * Same reasoning and ordering as {@link #verifyMfaToken}'s identical
     * fix — see its own Javadoc and {@link BruteForceProtectionService}'s
     * for the full account. Shares the same {@code Scope.MFA} counter as
     * TOTP verification, not a separate one — both are equally valid
     * ways to complete the same MFA step, so a failed guess at either
     * counts toward the same lockout; a determined attacker shouldn't
     * get twice the total guesses just by alternating between the two
     * verification methods.
     *
     * <p>Its own transaction and a refusal audited after it, as
     * {@link #verifyMfaToken}.
     */
    public LoginResponse verifyWithBackupCode(String rawMfaToken, String backupCode) {
        return completeOrRefuse(transaction.execute(status -> verifyBackupCodeInTransaction(
                rawMfaToken, backupCode, status)));
    }

    private MfaAttempt verifyBackupCodeInTransaction(String rawMfaToken, String backupCode,
                                                     TransactionStatus status) {
        final MfaSessionContext session =
                resolveAndCheckMfaSession(rawMfaToken, "backup code");

        final User user = session.token().getUser();

        final List<MfaBackupCode> unusedCodes =
                backupCodeRepository.findUnusedByUserId(user.getId());

        MfaBackupCode matched = null;
        for (final MfaBackupCode code : unusedCodes) {
            if (passwordEncoder.matches(backupCode, code.getCodeHash())) {
                matched = code;
                break;
            }
        }

        if (matched == null) {
            bruteForceProtectionService.recordFailure(
                    BruteForceProtectionService.Scope.MFA,
                    session.lockoutIdentifier(), session.tenantId());

            status.setRollbackOnly();
            return MfaAttempt.refused(user.getId(), session.tenantId(),
                    "MFA verification failed — invalid backup code", "Invalid or already used backup code");
        }

        bruteForceProtectionService.recordSuccess(
                BruteForceProtectionService.Scope.MFA,
                session.lockoutIdentifier(), session.tenantId());

        matched.markUsed();
        backupCodeRepository.save(matched);

        final long remaining = backupCodeRepository.countUnusedByUserId(user.getId());

        auditEventPublisher.publishAuth(
                user.getId(), session.tenantId(),
                AuditEventTypes.MFA_BACKUP_CODE_USED,
                "auth-service",
                user.getId().toString(),
                "MFA backup code used for login",
                Map.of("remainingCodes", String.valueOf(remaining)));

        log.warn("MFA backup code used: userId={}, tenant={}, remaining={}",
                user.getId(), session.tenantId(), remaining);

        return MfaAttempt.completed(issueTokens(user, session.tenantId()));
    }

    // ── Backup codes status ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public MfaBackupCodesStatusResponse getBackupCodesStatus(UserPrincipal principal) {
        final User user = requireUser(principal.userId(), TenantContext.get());
        if (!user.isMfaEnabled()) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "MFA is not enabled",
                    HttpStatus.CONFLICT);
        }
        return new MfaBackupCodesStatusResponse(
                backupCodeRepository.countUnusedByUserId(principal.userId()),
                user.getMfaEnabledAt());
    }

    // ── private ───────────────────────────────────────────────────────────

    /**
     * Shared logic between {@link #verifyMfaToken} and
     * {@link #verifyWithBackupCode} (backlog #60) — resolves the user
     * behind an MFA session token, checks brute-force lockout, and
     * consumes the token only once the request is confirmed to proceed.
     * See {@link #verifyMfaToken}'s own Javadoc for the full reasoning
     * behind this peek-then-check-then-consume ordering (backlog #58).
     *
     * @param rawMfaToken           the raw MFA_SESSION token from the request
     * @param verificationMethodLabel a short label ("TOTP" or "backup code")
     *                              used only to make the lockout log line
     *                              identify which verification path
     *                              triggered it — the two call sites
     *                              previously had near-identical but
     *                              subtly different wording here
     * @return the consumed token plus the lockout identifier/tenantId
     *         the caller needs for its own subsequent recordFailure/
     *         recordSuccess calls
     * @throws BusinessException 401 if currently locked out for this user
     */
    private MfaSessionContext resolveAndCheckMfaSession(
            String rawMfaToken, String verificationMethodLabel) {
        final AuthToken peeked = authTokenService.peekToken(
                rawMfaToken, AuthToken.Type.MFA_SESSION);
        final String tenantId = peeked.getTenantId();
        final String lockoutIdentifier = peeked.getUser().getId().toString();

        if (bruteForceProtectionService.isLocked(
                BruteForceProtectionService.Scope.MFA, lockoutIdentifier, tenantId)) {
            final Duration remaining = bruteForceProtectionService.getRemainingLockout(
                    BruteForceProtectionService.Scope.MFA, lockoutIdentifier, tenantId);
            log.warn("MFA {} verification rejected — locked out: userId={}, " +
                            "tenant={}, remainingSeconds={}",
                    verificationMethodLabel, peeked.getUser().getId(),
                    tenantId, remaining.toSeconds());
            throw new BusinessException(
                    ErrorCodes.UNAUTHORIZED,
                    String.format("Too many failed MFA attempts. Try again in %d minutes.",
                            remaining.toMinutes() + 1),
                    HttpStatus.UNAUTHORIZED);
        }

        final AuthToken consumed = authTokenService.consumeToken(
                rawMfaToken, AuthToken.Type.MFA_SESSION);

        return new MfaSessionContext(consumed, lockoutIdentifier, tenantId);
    }

    private record MfaSessionContext(
            AuthToken token, String lockoutIdentifier, String tenantId) {}

    /** A verification's outcome: tokens, or a refusal still to be audited. */
    private record MfaAttempt(LoginResponse response, UUID userId, String tenantId,
                              String auditDetail, String message) {

        static MfaAttempt completed(LoginResponse response) {
            return new MfaAttempt(response, null, null, null, null);
        }

        static MfaAttempt refused(UUID userId, String tenantId, String auditDetail, String message) {
            return new MfaAttempt(null, userId, tenantId, auditDetail, message);
        }
    }

    private LoginResponse completeOrRefuse(MfaAttempt attempt) {
        if (attempt.response() != null) {
            return attempt.response();
        }
        throw refuse(attempt);
    }

    /**
     * Audits a wrong code after its transaction rolled back, then refuses.
     *
     * <h2>Fixed (backlog #0-84, found in review)</h2>
     * With the audit outbox an event written inside the transaction is rolled
     * back with it, and a wrong code must roll back (the consumed token comes
     * back). The first fix wrote the refusal in a nested {@code REQUIRES_NEW}
     * transaction, which holds a second pooled connection while the first is
     * still open: with auth-service's pool of 5, a few concurrent wrong codes
     * could leave every request waiting for a connection. Written here, after
     * the transaction ended, the event commits on its own with one connection.
     * A failure to write it surfaces as an error instead of the 401: no
     * unaudited refusal.
     */
    private BusinessException refuse(MfaAttempt attempt) {
        auditEventPublisher.publishAuth(
                attempt.userId(), attempt.tenantId(),
                AuditEventTypes.MFA_VERIFY_FAILED,
                "auth-service",
                attempt.userId().toString(),
                attempt.auditDetail(),
                Map.of());
        return new BusinessException(ErrorCodes.UNAUTHORIZED, attempt.message(), HttpStatus.UNAUTHORIZED);
    }

    /**
     * Shared logic between {@link #setupMfa} and
     * {@link #setupMfaWithSetupToken} (backlog #60) — generate, encrypt,
     * and store a fresh pending secret, returning the QR response.
     *
     * <p>The 409 "already enabled" message is deliberately unified to
     * the more informative of the two previously-slightly-different
     * strings ("...Disable it first before reconfiguring.") — purely
     * cosmetic wording, not user- or endpoint-specific information, so
     * harmonizing it is a strict improvement with no meaningful
     * behavior change. Contrast {@link #doEnableMfa}'s "no pending
     * setup" message, which is NOT unified, since that difference
     * genuinely carries different, correct information per caller (which
     * endpoint to call next).
     */
    private MfaSetupResponse doSetupMfa(User user, String tenantId) {
        if (user.isMfaEnabled()) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "MFA is already enabled. Disable it first before reconfiguring.",
                    HttpStatus.CONFLICT);
        }

        final String plainSecret     = totpService.generateSecret();
        final String encryptedSecret = aesEncryptionService.encrypt(plainSecret);

        user.storePendingMfaSecret(encryptedSecret);
        userRepository.save(user);

        final String qrUrl = totpService.generateQrUrl(
                plainSecret, user.getEmail(), tenantId);

        return new MfaSetupResponse(qrUrl, plainSecret);
    }

/**
 * Shared logic between {@link #enableMfa} and
 * {@link #enableMfaWithSetupToken} (backlog #60) — verify the
 * pending secret against the supplied TOTP code, activate MFA, and
 * generate backup codes.
 *
 * @param noPendingSetupMessage the 409 message when no pending secret
 *                              exists — deliberately NOT unified
 *                              across callers, since each correctly
 *                              names a different endpoint the caller
 *                              should have called first
 * @param auditDescription     the {@code AuditEventTypes.MFA_ENABLED}
 *                              description — kept caller-specific so
 *                              the audit trail can distinguish the
 *                              ordinary setup flow from the
 *                              tenant-required, login-completing one
 * @return the plain-text backup codes — shown to the caller once
 */
private List<String> doEnableMfa(User user, String tenantId, String totpCode,
                                 String noPendingSetupMessage,
                                 String auditDescription) {
    if (user.getMfaPendingSecret() == null) {
        throw new BusinessException(
                ErrorCodes.BUSINESS_RULE_VIOLATION,
                noPendingSetupMessage,
                HttpStatus.CONFLICT);
    }

    final String plainSecret = aesEncryptionService.decrypt(
            user.getMfaPendingSecret());

    if (!verifyTotpAndRecordUsage(user, plainSecret, totpCode)) {
        throw new BusinessException(
                ErrorCodes.UNAUTHORIZED,
                "Invalid TOTP code. Verify your authenticator app clock is synced.",
                HttpStatus.UNAUTHORIZED);
    }

    user.enableMfa();
    userRepository.save(user);
    // Backlog #0-83: tell the account's address, so an owner whose password
    // was used to enrol someone else's factor finds out (sent by the outbox
    // after this commits).
    authEmailRequestService.requestMfaChangeNotification(user, true);

    final List<String> plainCodes = totpService.generateBackupCodes();
    saveBackupCodes(user, plainCodes);

    auditEventPublisher.publishAuth(
            user.getId(), tenantId,
            AuditEventTypes.MFA_ENABLED,
            "auth-service",
            user.getId().toString(),
            auditDescription,
            Map.of());

    return plainCodes;
}

    /**
     * Verifies a TOTP code against a decrypted secret AND checks it
     * hasn't already been consumed (backlog #59).
     *
     * <h2>Fixed (backlog #59): TOTP replay protection</h2>
     * {@link TotpService#verify} checks whether a code matches any of
     * 3 valid time-step windows (~90s tolerance) but has no state of
     * its own to track which step it has already accepted for a given
     * user (see its own Javadoc for why that tracking belongs here
     * instead). Without this check, a captured, still-valid code
     * (shoulder-surfing, malware, MITM) could be replayed by a second,
     * independent verification attempt within that same window and
     * would be accepted again.
     *
     * <p>This is the single choke point all four TOTP-verifying flows
     * in this class route through ({@link #enableMfa},
     * {@link #disableMfa}, {@link #verifyMfaToken},
     * {@link #enableMfaWithSetupToken}) — a code accepted for any one
     * purpose cannot be replayed against any of the others either.
     *
     * <p>On success, records the matched time step on {@code user}.
     * The caller remains responsible for persisting {@code user}
     * afterward, exactly as before this fix — this method does not
     * call {@code userRepository.save} itself, to avoid an extra,
     * redundant write on call sites that already save {@code user} for
     * other reasons in the same method.
     *
     * @return true if the code is valid AND not a replay of an
     *         already-accepted step; false otherwise. A rejected replay
     *         is deliberately indistinguishable from an ordinary wrong
     *         code to the caller (and therefore to the API response) —
     *         an attacker probing with a known-once-valid code should
     *         learn nothing from the response that a genuinely wrong
     *         guess wouldn't also reveal.
     */
    private boolean verifyTotpAndRecordUsage(User user, String plainSecret, String totpCode) {
        final Optional<Long> matchedStep = totpService.verify(plainSecret, totpCode);
        if (matchedStep.isEmpty()) {
            return false;
        }

        final Long lastUsed = user.getMfaLastUsedTimeStep();
        if (lastUsed != null && matchedStep.get() <= lastUsed) {
            log.warn("TOTP replay rejected: userId={}, tenant={}, " +
                            "matchedStep={}, lastUsedStep={}",
                    user.getId(), user.getTenantId(), matchedStep.get(), lastUsed);
            return false;
        }

        user.recordMfaTimeStep(matchedStep.get());
        return true;
    }

    private LoginResponse issueTokens(User user, String tenantId) {
        final List<UUID> teamIds =
                teamMemberRepository.findTeamIdsByUserIdAndTenantId(
                        user.getId(), tenantId);
        final List<UUID> managedTeamIds =
                teamMemberRepository.findManagedTeamIdsByUserIdAndTenantId(
                        user.getId(), tenantId);

        // Generated once per completed login, shared by the access token
        // (claim) and the refresh token (column) issued together below —
        // same reasoning as AuthService.login()'s identical sessionId
        // generation; see AuthToken.sessionId's own Javadoc for the full
        // account.
        final UUID sessionId = UUID.randomUUID();

        final String accessToken = jwtUtils.generateToken(
                user.getId(), tenantId,
                user.getEmail(), user.getRoleNames(), teamIds, managedTeamIds,
                sessionId);

        final Instant accessExpiresAt  = Instant.now().plus(jwtUtils.getAccessTokenTtl());
        final String rawRefreshToken   =
                // MFA verified now: every caller of this method has just
                // verified a TOTP or backup code (verifyMfaToken,
                // enableMfaWithSetupToken, verifyWithBackupCode); the
                // platform API requires it of the session (backlog #0-83).
                authTokenService.generateRefreshToken(user, tenantId, sessionId, Instant.now());
        final Instant refreshExpiresAt = Instant.now().plus(jwtUtils.getRefreshTokenTtl());

        auditEventPublisher.publishAuth(
                user.getId(), tenantId,
                AuditEventTypes.MFA_VERIFY_SUCCESS,
                "auth-service",
                user.getId().toString(),
                "MFA verification successful",
                Map.of());

        log.info("MFA verified, tokens issued: userId={}, tenant={}",
                user.getId(), tenantId);

        return LoginResponse.success(
                accessToken, rawRefreshToken,
                user.getId(), tenantId,
                user.getEmail(), user.getRoleNames(),
                accessExpiresAt, refreshExpiresAt);
    }

    /**
     * Removes the user's factor, shared by {@link #disableMfa} and the
     * resets. The user's sessions no longer count as MFA-verified, so the
     * platform API stops accepting them now, not at their next login
     * (backlog #0-83). The caller queues the email that fits its case.
     */
    private void clearFactor(User user, String tenantId) {
        user.disableMfa();
        userRepository.save(user);
        backupCodeRepository.deleteAllByUserId(user.getId());
        authTokenService.forgetMfaOfAllSessions(user.getId(), tenantId);
    }

    /**
     * The reset of an operator's MFA recovery request (backlog #0-90), called
     * by {@code MfaRecoveryService} in its transaction once the waiting period
     * has passed: what an admin reset does, and the password goes too.
     *
     * <p>Why the password: the admin's factor may be a stranger's, enrolled
     * with a stolen password, and the stranger may still have that password.
     * An admin reset (#0-88) relies on the owner resetting the password first;
     * a recovery cannot, as nobody inside the tenant checks the order. The
     * password is replaced with the hash of 32 random bytes nobody keeps, not
     * with null: a null password means "invite not accepted" across
     * auth-service (the tenant would look admin-less, #0-80), whereas this
     * account stays an accepted one that simply cannot log in until its owner
     * sets a new password. The owner is emailed MFA_RECOVERY_COMPLETED, which
     * carries a password-reset link (otherwise forgot-password), then logs in
     * with the new password alone and enrols a factor again.
     *
     * @return the number of personal API keys revoked, for the audit event
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int resetForRecovery(User user, String tenantId) {
        final byte[] unusable = new byte[32];
        secureRandom.nextBytes(unusable);
        user.setPasswordHash(passwordEncoder.encode(HexFormat.of().formatHex(unusable)));
        user.discardPendingMfaSecret();
        return resetFactorAndSessions(user, tenantId, true);
    }

    /**
     * Shared by {@link #resetMfaByAdmin}, {@link #resetMfaBreakGlass} and
     * {@link #resetForRecovery}: the factor goes, and so does every session
     * and unfinished login of the user (the usual reason is a compromised
     * account), and the user gets the email of the case: "an administrator
     * reset your MFA", or for a recovery (backlog #0-90) "your account was
     * recovered, set a new password".
     *
     * <p>Backlog #0-89: the user's personal API keys are revoked too. A key
     * created by whoever had the password would otherwise outlive the
     * recovery; the email says so. Last, because the bulk revocation clears
     * the persistence context.
     *
     * @return the number of personal API keys revoked, for the audit event
     */
    private int resetFactorAndSessions(User user, String tenantId, boolean recovery) {
        clearFactor(user, tenantId);
        if (recovery) {
            authEmailRequestService.requestMfaRecoveryCompleted(user);
        } else {
            authEmailRequestService.requestMfaResetNotification(user);
        }
        authTokenService.invalidateLoginContinuationTokens(user.getId());
        authTokenService.invalidateAllRefreshTokens(user.getId());
        return apiKeyService.revokeAllPersonalKeysForUser(user.getId(), tenantId);
    }

    private void saveBackupCodes(User user, List<String> plainCodes) {
        final List<MfaBackupCode> entities = new java.util.ArrayList<>();
        for (final String plain : plainCodes) {
            entities.add(MfaBackupCode.create(user, passwordEncoder.encode(plain)));
        }
        backupCodeRepository.saveAll(entities);
    }

    /**
     * Enrolling a factor needs a live login session, not just an unexpired
     * access token (backlog #0-83, found in review): after a password reset
     * ended every session, a token stolen before it could otherwise still add
     * a factor for up to 15 minutes, leaving the owner locked out of the
     * account they just recovered.
     */
    private void requireLiveSession(UserPrincipal principal) {
        if (!authTokenService.isSessionLive(principal.userId(), principal.tenantId(), principal.sessionId())) {
            throw new BusinessException(
                    ErrorCodes.UNAUTHORIZED,
                    "This login session has ended. Log in again to set up MFA.",
                    HttpStatus.UNAUTHORIZED);
        }
    }

    private User requireUser(UUID userId, String tenantId) {
        return userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }
}