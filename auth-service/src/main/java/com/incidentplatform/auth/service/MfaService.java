package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.MfaBackupCode;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.*;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    public MfaService(UserRepository userRepository,
                      MfaBackupCodeRepository backupCodeRepository,
                      AuthTokenService authTokenService,
                      TeamMemberRepository teamMemberRepository,
                      TotpService totpService,
                      AesEncryptionService aesEncryptionService,
                      PasswordEncoder passwordEncoder,
                      JwtUtils jwtUtils,
                      AuditEventPublisher auditEventPublisher) {
        this.userRepository       = userRepository;
        this.backupCodeRepository = backupCodeRepository;
        this.authTokenService     = authTokenService;
        this.teamMemberRepository = teamMemberRepository;
        this.totpService          = totpService;
        this.aesEncryptionService = aesEncryptionService;
        this.passwordEncoder      = passwordEncoder;
        this.jwtUtils             = jwtUtils;
        this.auditEventPublisher  = auditEventPublisher;
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

        if (!totpService.verify(plainSecret, totpCode)) {
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
        if (!totpService.verify(plainSecret, totpCode)) {
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
     */
    @Transactional
    public LoginResponse verifyMfaToken(String rawMfaToken, String totpCode) {
        final AuthToken mfaToken = authTokenService.consumeToken(
                rawMfaToken, AuthToken.Type.MFA_SESSION);

        final User user     = mfaToken.getUser();
        final String tenantId = mfaToken.getTenantId();

        final String plainSecret = aesEncryptionService.decrypt(user.getMfaSecret());

        if (!totpService.verify(plainSecret, totpCode)) {
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

        if (!totpService.verify(plainSecret, totpCode)) {
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
     */
    @Transactional
    public LoginResponse verifyWithBackupCode(String rawMfaToken, String backupCode) {
        final AuthToken mfaToken = authTokenService.consumeToken(
                rawMfaToken, AuthToken.Type.MFA_SESSION);

        final User user     = mfaToken.getUser();
        final String tenantId = mfaToken.getTenantId();

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

    private LoginResponse issueTokens(User user, String tenantId) {
        final List<UUID> teamIds =
                teamMemberRepository.findTeamIdsByUserIdAndTenantId(
                        user.getId(), tenantId);

        final String accessToken = jwtUtils.generateToken(
                user.getId(), tenantId,
                user.getEmail(), user.getRoleNames(), teamIds);

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