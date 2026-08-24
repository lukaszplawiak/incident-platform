package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.MfaBackupCode;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.LoginResponse;
import com.incidentplatform.auth.dto.MfaBackupCodesStatusResponse;
import com.incidentplatform.auth.dto.MfaEnableResponse;
import com.incidentplatform.auth.dto.MfaEnableWithLoginResponse;
import com.incidentplatform.auth.dto.MfaSetupResponse;
import com.incidentplatform.auth.ratelimit.BruteForceProtectionService;
import com.incidentplatform.auth.repository.MfaBackupCodeRepository;
import com.incidentplatform.auth.repository.TeamMemberRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    private final UserRepository userRepository;
    private final MfaBackupCodeRepository backupCodeRepository;
    private final AuthTokenService authTokenService;
    private final TeamMemberRepository teamMemberRepository;
    private final TotpService totpService;
    private final AesEncryptionService aesEncryptionService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final AuditEventPublisher auditEventPublisher;
    private final BruteForceProtectionService bruteForceProtectionService;

    public MfaService(UserRepository userRepository,
                      MfaBackupCodeRepository backupCodeRepository,
                      AuthTokenService authTokenService,
                      TeamMemberRepository teamMemberRepository,
                      TotpService totpService,
                      AesEncryptionService aesEncryptionService,
                      PasswordEncoder passwordEncoder,
                      JwtUtils jwtUtils,
                      AuditEventPublisher auditEventPublisher,
                      BruteForceProtectionService bruteForceProtectionService) {
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
        final String tenantId = TenantContext.get();
        final User user = requireUser(principal.userId(), tenantId);

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

        log.info("MFA setup initiated: userId={}, tenant={}", principal.userId(), tenantId);

        return new MfaSetupResponse(qrUrl, plainSecret);
    }

    // ── Enable (step 2) ───────────────────────────────────────────────────

    /**
     * Activates MFA after the user confirms the TOTP code from their app.
     *
     * @return backup codes (plain) — shown once, stored as Argon2 hashes
     */
    @Transactional
    public MfaEnableResponse enableMfa(String totpCode, UserPrincipal principal) {
        final String tenantId = TenantContext.get();
        final User user = requireUser(principal.userId(), tenantId);

        if (user.getMfaPendingSecret() == null) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "No pending MFA setup found. Call POST /auth/mfa/setup first.",
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

        final List<String> plainCodes = totpService.generateBackupCodes();
        saveBackupCodes(user, plainCodes);

        auditEventPublisher.publishAuth(
                principal.userId(), tenantId,
                AuditEventTypes.MFA_ENABLED,
                "auth-service",
                principal.userId().toString(),
                "MFA enabled",
                Map.of());

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

        user.disableMfa();
        userRepository.save(user);
        backupCodeRepository.deleteAllByUserId(principal.userId());

        auditEventPublisher.publishAuth(
                principal.userId(), tenantId,
                AuditEventTypes.MFA_DISABLED,
                "auth-service",
                principal.userId().toString(),
                "MFA disabled",
                Map.of());

        log.info("MFA disabled: userId={}, tenant={}", principal.userId(), tenantId);
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
     */
    @Transactional
    public LoginResponse verifyMfaToken(String rawMfaToken, String totpCode) {
        final AuthToken peeked = authTokenService.peekToken(
                rawMfaToken, AuthToken.Type.MFA_SESSION);
        final User peekedUser = peeked.getUser();
        final String tenantId = peeked.getTenantId();
        final String lockoutIdentifier = peekedUser.getId().toString();

        if (bruteForceProtectionService.isLocked(
                BruteForceProtectionService.Scope.MFA, lockoutIdentifier, tenantId)) {
            final Duration remaining = bruteForceProtectionService.getRemainingLockout(
                    BruteForceProtectionService.Scope.MFA, lockoutIdentifier, tenantId);
            log.warn("MFA verification rejected — locked out: userId={}, " +
                            "tenant={}, remainingSeconds={}",
                    peekedUser.getId(), tenantId, remaining.toSeconds());
            throw new BusinessException(
                    ErrorCodes.UNAUTHORIZED,
                    String.format("Too many failed MFA attempts. Try again in %d minutes.",
                            remaining.toMinutes() + 1),
                    HttpStatus.UNAUTHORIZED);
        }

        final AuthToken mfaToken = authTokenService.consumeToken(
                rawMfaToken, AuthToken.Type.MFA_SESSION);

        final User user     = mfaToken.getUser();

        final String plainSecret = aesEncryptionService.decrypt(user.getMfaSecret());

        if (!verifyTotpAndRecordUsage(user, plainSecret, totpCode)) {
            bruteForceProtectionService.recordFailure(
                    BruteForceProtectionService.Scope.MFA, lockoutIdentifier, tenantId);

            auditEventPublisher.publishAuth(
                    user.getId(), tenantId,
                    AuditEventTypes.MFA_VERIFY_FAILED,
                    "auth-service",
                    user.getId().toString(),
                    "MFA verification failed — invalid TOTP code",
                    Map.of());

            throw new BusinessException(
                    ErrorCodes.UNAUTHORIZED,
                    "Invalid TOTP code",
                    HttpStatus.UNAUTHORIZED);
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
                BruteForceProtectionService.Scope.MFA, lockoutIdentifier, tenantId);

        return issueTokens(user, tenantId);
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

        if (user.isMfaEnabled()) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "MFA is already enabled.",
                    HttpStatus.CONFLICT);
        }

        final String plainSecret     = totpService.generateSecret();
        final String encryptedSecret = aesEncryptionService.encrypt(plainSecret);

        user.storePendingMfaSecret(encryptedSecret);
        userRepository.save(user);

        final String qrUrl = totpService.generateQrUrl(
                plainSecret, user.getEmail(), tenantId);

        log.info("MFA setup (tenant-required flow) initiated: userId={}, tenant={}",
                user.getId(), tenantId);

        return new MfaSetupResponse(qrUrl, plainSecret);
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

        if (user.getMfaPendingSecret() == null) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "No pending MFA setup found. Call POST /auth/mfa/setup-required first.",
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

        final List<String> plainCodes = totpService.generateBackupCodes();
        saveBackupCodes(user, plainCodes);

        auditEventPublisher.publishAuth(
                user.getId(), tenantId,
                AuditEventTypes.MFA_ENABLED,
                "auth-service",
                user.getId().toString(),
                "MFA enabled (tenant-required flow, login completed)",
                Map.of());

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
 */
@Transactional
public LoginResponse verifyWithBackupCode(String rawMfaToken, String backupCode) {
    final AuthToken peeked = authTokenService.peekToken(
            rawMfaToken, AuthToken.Type.MFA_SESSION);
    final User peekedUser = peeked.getUser();
    final String tenantId = peeked.getTenantId();
    final String lockoutIdentifier = peekedUser.getId().toString();

    if (bruteForceProtectionService.isLocked(
            BruteForceProtectionService.Scope.MFA, lockoutIdentifier, tenantId)) {
        final Duration remainingLockout = bruteForceProtectionService.getRemainingLockout(
                BruteForceProtectionService.Scope.MFA, lockoutIdentifier, tenantId);
        log.warn("MFA backup code verification rejected — locked out: " +
                        "userId={}, tenant={}, remainingSeconds={}",
                peekedUser.getId(), tenantId, remainingLockout.toSeconds());
        throw new BusinessException(
                ErrorCodes.UNAUTHORIZED,
                String.format("Too many failed MFA attempts. Try again in %d minutes.",
                        remainingLockout.toMinutes() + 1),
                HttpStatus.UNAUTHORIZED);
    }

    final AuthToken mfaToken = authTokenService.consumeToken(
            rawMfaToken, AuthToken.Type.MFA_SESSION);

    final User user     = mfaToken.getUser();

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
                BruteForceProtectionService.Scope.MFA, lockoutIdentifier, tenantId);

        auditEventPublisher.publishAuth(
                user.getId(), tenantId,
                AuditEventTypes.MFA_VERIFY_FAILED,
                "auth-service",
                user.getId().toString(),
                "MFA verification failed — invalid backup code",
                Map.of());

        throw new BusinessException(
                ErrorCodes.UNAUTHORIZED,
                "Invalid or already used backup code",
                HttpStatus.UNAUTHORIZED);
    }

    bruteForceProtectionService.recordSuccess(
            BruteForceProtectionService.Scope.MFA, lockoutIdentifier, tenantId);

    matched.markUsed();
    backupCodeRepository.save(matched);

    final long remaining = backupCodeRepository.countUnusedByUserId(user.getId());

    auditEventPublisher.publishAuth(
            user.getId(), tenantId,
            AuditEventTypes.MFA_BACKUP_CODE_USED,
            "auth-service",
            user.getId().toString(),
            "MFA backup code used for login",
            Map.of("remainingCodes", String.valueOf(remaining)));

    log.warn("MFA backup code used: userId={}, tenant={}, remaining={}",
            user.getId(), tenantId, remaining);

    return issueTokens(user, tenantId);
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

        final String accessToken = jwtUtils.generateToken(
                user.getId(), tenantId,
                user.getEmail(), user.getRoleNames(), teamIds, managedTeamIds);

        final Instant accessExpiresAt  = Instant.now().plus(jwtUtils.getAccessTokenTtl());
        final String rawRefreshToken   = authTokenService.generateRefreshToken(user, tenantId);
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

    private void saveBackupCodes(User user, List<String> plainCodes) {
        final List<MfaBackupCode> entities = new java.util.ArrayList<>();
        for (final String plain : plainCodes) {
            entities.add(MfaBackupCode.create(user, passwordEncoder.encode(plain)));
        }
        backupCodeRepository.saveAll(entities);
    }

    private User requireUser(UUID userId, String tenantId) {
        return userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }
}