package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.MfaBackupCode;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.MfaEnableResponse;
import com.incidentplatform.auth.dto.MfaEnableWithLoginResponse;
import com.incidentplatform.auth.dto.MfaSetupResponse;
import com.incidentplatform.auth.dto.LoginResponse;
import com.incidentplatform.auth.ratelimit.BruteForceProtectionService;
import com.incidentplatform.auth.ratelimit.MfaResetRateLimiter;
import com.incidentplatform.auth.ratelimit.RateLimitDecision;
import com.incidentplatform.auth.ratelimit.RateLimitRefusedException;
import com.incidentplatform.auth.repository.MfaBackupCodeRepository;
import com.incidentplatform.auth.repository.TeamMemberRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("MfaService")
class MfaServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private MfaBackupCodeRepository backupCodeRepository;
    @Mock private AuthTokenService authTokenService;
    @Mock private TeamMemberRepository teamMemberRepository;
    @Mock private TotpService totpService;
    @Mock private AesEncryptionService aesEncryptionService;
    @Mock private AuditEventPublisher auditEventPublisher;
    @Mock private JwtUtils jwtUtils;
    @Mock private BruteForceProtectionService bruteForceProtectionService;
    @Mock private AuthEmailRequestService authEmailRequestService;
    @Mock private MfaSessionStatusService mfaSessionStatusService;
    @Mock private MfaResetRateLimiter mfaResetRateLimiter;
    @Mock private ApiKeyService apiKeyService;

    private final PasswordEncoder passwordEncoder =
            Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    private MfaService service;

    /** The verify methods' own transaction: records whether it was rolled back. */
    private final org.springframework.transaction.PlatformTransactionManager transactionManager =
            org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
    private final java.util.List<org.springframework.transaction.support.SimpleTransactionStatus> transactions =
            new java.util.ArrayList<>();

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final String TENANT_ID = "test-tenant";
    private static final UUID   USER_ID   = UUID.randomUUID();
    private static final UUID   ADMIN_ID  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MfaService(
                userRepository, backupCodeRepository, authTokenService,
                teamMemberRepository, totpService, aesEncryptionService,
                passwordEncoder, jwtUtils, auditEventPublisher,
                bruteForceProtectionService, authEmailRequestService, mfaSessionStatusService,
                mfaResetRateLimiter, apiKeyService, transactionManager);
        org.mockito.Mockito.lenient().when(transactionManager.getTransaction(any())).thenAnswer(i -> {
            final var status = new org.springframework.transaction.support.SimpleTransactionStatus();
            transactions.add(status);
            return status;
        });
        TenantContext.set(TENANT_ID);
        org.mockito.Mockito.lenient().when(authTokenService.isSessionLive(USER_ID, TENANT_ID, SESSION_ID)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * Backlog #0-84 (found in review): a wrong code rolls its transaction back
     * (the consumed token comes back) and is audited after it ended, with one
     * connection, not in a nested transaction holding a second.
     */
    private void assertRefusalAuditedAfterRollback() {
        assertThat(transactions).hasSize(1);
        assertThat(transactions.get(0).isRollbackOnly()).as("wrong code rolls back").isTrue();
        final org.mockito.InOrder order = org.mockito.Mockito.inOrder(transactionManager, auditEventPublisher);
        order.verify(transactionManager).commit(transactions.get(0));
        order.verify(auditEventPublisher).publishAuth(eq(USER_ID), eq(TENANT_ID),
                eq(AuditEventTypes.MFA_VERIFY_FAILED), eq("auth-service"), eq(USER_ID.toString()),
                anyString(), any());
    }

    // ── setupMfa ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setupMfa")
    class SetupMfa {

        @Test
        @DisplayName("generates secret, stores as pending and returns QR URL")
        void generatesSecretAndQrUrl() {
            final User user = buildUser(false);
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(totpService.generateSecret()).willReturn("BASE32SECRET");
            given(aesEncryptionService.encrypt("BASE32SECRET")).willReturn("encrypted");
            given(totpService.generateQrUrl(anyString(), anyString(), anyString()))
                    .willReturn("otpauth://totp/...");
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            final MfaSetupResponse response = service.setupMfa(buildPrincipal());

            assertThat(response.secret()).isEqualTo("BASE32SECRET");
            assertThat(response.qrUrl()).startsWith("otpauth://");
            assertThat(user.getMfaPendingSecret()).isEqualTo("encrypted");
        }

        @Test
        @DisplayName("throws 409 when MFA already enabled")
        void throws409WhenAlreadyEnabled() {
            final User user = buildUser(true);
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));

            assertThatThrownBy(() -> service.setupMfa(buildPrincipal()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.CONFLICT);
        }
    }

    // ── enableMfa ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("enableMfa")
    class EnableMfa {

        @Test
        @DisplayName("enables MFA and returns backup codes on valid TOTP code")
        void enablesMfaAndReturnsBackupCodes() {
            final User user = buildUser(false);
            user.storePendingMfaSecret("encrypted-secret");
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(aesEncryptionService.decrypt("encrypted-secret"))
                    .willReturn("PLAIN_SECRET");
            given(totpService.verify("PLAIN_SECRET", "123456")).willReturn(Optional.of(100L));
            given(totpService.generateBackupCodes())
                    .willReturn(List.of("code1", "code2", "code3",
                            "code4", "code5", "code6", "code7", "code8"));
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));
            given(backupCodeRepository.saveAll(any())).willAnswer(i -> i.getArgument(0));

            final MfaEnableResponse response =
                    service.enableMfa("123456", buildPrincipal());

            assertThat(response.backupCodes()).hasSize(8);
            assertThat(user.isMfaEnabled()).isTrue();
            assertThat(user.getMfaSecret()).isEqualTo("encrypted-secret");
            assertThat(user.getMfaPendingSecret()).isNull();
            // Backlog #0-83: the account's address is told a factor was enrolled.
            then(authEmailRequestService).should().requestMfaChangeNotification(user, true);
        }

        @Test
        @DisplayName("throws 401 on invalid TOTP code")
        void throws401OnInvalidCode() {
            final User user = buildUser(false);
            user.storePendingMfaSecret("encrypted-secret");
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(aesEncryptionService.decrypt("encrypted-secret"))
                    .willReturn("PLAIN_SECRET");
            given(totpService.verify("PLAIN_SECRET", "999999")).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.enableMfa("999999", buildPrincipal()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            then(authEmailRequestService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("throws 409 when no pending setup found")
        void throws409WhenNoPendingSetup() {
            final User user = buildUser(false); // no pending secret
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));

            assertThatThrownBy(() -> service.enableMfa("123456", buildPrincipal()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.CONFLICT);
        }
    }

    // ── verifyMfaToken (backlog #58) ────────────────────────────────────

    /**
     * No prior test coverage existed for this method before backlog #58 —
     * added alongside the lockout fix itself.
     */
    @Nested
    @DisplayName("verifyMfaToken")
    class VerifyMfaToken {

        @Test
        @DisplayName("returns LoginResponse and clears the failure counter on valid TOTP code")
        void verifiesAndIssuesTokens() {
            final User user = buildUser(true);
            final AuthToken mfaSessionToken = AuthToken.forTesting(
                    user, TENANT_ID, "hash", AuthToken.Type.MFA_SESSION,
                    Instant.now().plusSeconds(300), null);

            given(authTokenService.peekToken("raw-mfa-token", AuthToken.Type.MFA_SESSION))
                    .willReturn(mfaSessionToken);
            given(bruteForceProtectionService.isLocked(
                    BruteForceProtectionService.Scope.MFA, USER_ID.toString(), TENANT_ID))
                    .willReturn(false);
            given(authTokenService.consumeToken("raw-mfa-token", AuthToken.Type.MFA_SESSION))
                    .willReturn(mfaSessionToken);
            given(aesEncryptionService.decrypt("encrypted-secret")).willReturn("PLAIN_SECRET");
            given(totpService.verify("PLAIN_SECRET", "123456")).willReturn(Optional.of(100L));
            given(teamMemberRepository.findTeamIdsByUserIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(List.of());
            given(teamMemberRepository.findManagedTeamIdsByUserIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(List.of());
            given(jwtUtils.generateToken(any(), anyString(), anyString(), any(), any(), any(), any()))
                    .willReturn("access-token");
            given(jwtUtils.getAccessTokenTtl()).willReturn(java.time.Duration.ofMinutes(15));
            given(jwtUtils.getRefreshTokenTtl()).willReturn(java.time.Duration.ofDays(30));
            given(authTokenService.generateRefreshToken(any(), anyString(), any(), notNull()))
                    .willReturn("refresh-token");

            final LoginResponse response =
                    service.verifyMfaToken("raw-mfa-token", "123456");

            assertThat(response.accessToken()).isEqualTo("access-token");
            then(bruteForceProtectionService).should().recordSuccess(
                    BruteForceProtectionService.Scope.MFA, USER_ID.toString(), TENANT_ID);
        }

        @Test
        @DisplayName("throws 401 and records a failure on invalid TOTP code")
        void throws401AndRecordsFailureOnInvalidCode() {
            final User user = buildUser(true);
            final AuthToken mfaSessionToken = AuthToken.forTesting(
                    user, TENANT_ID, "hash", AuthToken.Type.MFA_SESSION,
                    Instant.now().plusSeconds(300), null);

            given(authTokenService.peekToken("raw-mfa-token", AuthToken.Type.MFA_SESSION))
                    .willReturn(mfaSessionToken);
            given(bruteForceProtectionService.isLocked(
                    BruteForceProtectionService.Scope.MFA, USER_ID.toString(), TENANT_ID))
                    .willReturn(false);
            given(authTokenService.consumeToken("raw-mfa-token", AuthToken.Type.MFA_SESSION))
                    .willReturn(mfaSessionToken);
            given(aesEncryptionService.decrypt("encrypted-secret")).willReturn("PLAIN_SECRET");
            given(totpService.verify("PLAIN_SECRET", "000000")).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.verifyMfaToken("raw-mfa-token", "000000"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);

            then(bruteForceProtectionService).should().recordFailure(
                    BruteForceProtectionService.Scope.MFA, USER_ID.toString(), TENANT_ID);
            assertRefusalAuditedAfterRollback();
        }

        /**
         * The actual regression test for backlog #58 — verifies a
         * locked-out request is rejected WITHOUT ever consuming the
         * single-use MFA session token, so a legitimate user isn't
         * additionally penalized (their unexpired token stays valid for
         * once the lockout clears).
         */
        @Test
        @DisplayName("throws 401 without consuming the token when locked out (backlog #58)")
        void throws401WhenLockedOutWithoutConsumingToken() {
            final User user = buildUser(true);
            final AuthToken mfaSessionToken = AuthToken.forTesting(
                    user, TENANT_ID, "hash", AuthToken.Type.MFA_SESSION,
                    Instant.now().plusSeconds(300), null);

            given(authTokenService.peekToken("raw-mfa-token", AuthToken.Type.MFA_SESSION))
                    .willReturn(mfaSessionToken);
            given(bruteForceProtectionService.isLocked(
                    BruteForceProtectionService.Scope.MFA, USER_ID.toString(), TENANT_ID))
                    .willReturn(true);
            given(bruteForceProtectionService.getRemainingLockout(
                    BruteForceProtectionService.Scope.MFA, USER_ID.toString(), TENANT_ID))
                    .willReturn(java.time.Duration.ofMinutes(5));

            assertThatThrownBy(() -> service.verifyMfaToken("raw-mfa-token", "123456"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);

            then(authTokenService).should(org.mockito.Mockito.never())
                    .consumeToken(anyString(), any());
            then(totpService).should(org.mockito.Mockito.never())
                    .verify(any(), any());
        }

        /**
         * The actual regression test for backlog #59 — a code that is
         * cryptographically valid but whose matched time step was
         * already accepted by a prior verification must be rejected as
         * a replay, and treated identically to an ordinary wrong code
         * (same exception, same brute-force failure recording) so an
         * attacker probing with a known-once-valid code learns nothing
         * from the response.
         */
        @Test
        @DisplayName("rejects a replayed TOTP code — same matched time step as a " +
                "previously accepted verification (backlog #59)")
        void rejectsReplayedTotpCode() {
            final User user = buildUser(true);
            user.recordMfaTimeStep(100L); // simulates an earlier accepted verification
            final AuthToken mfaSessionToken = AuthToken.forTesting(
                    user, TENANT_ID, "hash", AuthToken.Type.MFA_SESSION,
                    Instant.now().plusSeconds(300), null);

            given(authTokenService.peekToken("raw-mfa-token", AuthToken.Type.MFA_SESSION))
                    .willReturn(mfaSessionToken);
            given(bruteForceProtectionService.isLocked(
                    BruteForceProtectionService.Scope.MFA, USER_ID.toString(), TENANT_ID))
                    .willReturn(false);
            given(authTokenService.consumeToken("raw-mfa-token", AuthToken.Type.MFA_SESSION))
                    .willReturn(mfaSessionToken);
            given(aesEncryptionService.decrypt("encrypted-secret")).willReturn("PLAIN_SECRET");
            // Cryptographically valid — matches time step 100 — but that
            // step was already accepted once before (see recordMfaTimeStep
            // above), so this must be rejected as a replay.
            given(totpService.verify("PLAIN_SECRET", "123456"))
                    .willReturn(Optional.of(100L));

            assertThatThrownBy(() -> service.verifyMfaToken("raw-mfa-token", "123456"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);

            then(bruteForceProtectionService).should().recordFailure(
                    BruteForceProtectionService.Scope.MFA, USER_ID.toString(), TENANT_ID);
            // The tracked step must not be disturbed by a rejected replay.
            assertThat(user.getMfaLastUsedTimeStep()).isEqualTo(100L);
        }

        /**
         * Confirms the fix doesn't over-reject: a genuinely new code —
         * matching a LATER time step than the one previously accepted —
         * must still succeed, and the tracked step must advance to the
         * new value.
         */
        @Test
        @DisplayName("accepts a code matching a newer time step and advances the " +
                "tracked step (backlog #59)")
        void acceptsNewerTimeStepAndAdvancesTracking() {
            final User user = buildUser(true);
            user.recordMfaTimeStep(100L);
            final AuthToken mfaSessionToken = AuthToken.forTesting(
                    user, TENANT_ID, "hash", AuthToken.Type.MFA_SESSION,
                    Instant.now().plusSeconds(300), null);

            given(authTokenService.peekToken("raw-mfa-token", AuthToken.Type.MFA_SESSION))
                    .willReturn(mfaSessionToken);
            given(bruteForceProtectionService.isLocked(
                    BruteForceProtectionService.Scope.MFA, USER_ID.toString(), TENANT_ID))
                    .willReturn(false);
            given(authTokenService.consumeToken("raw-mfa-token", AuthToken.Type.MFA_SESSION))
                    .willReturn(mfaSessionToken);
            given(aesEncryptionService.decrypt("encrypted-secret")).willReturn("PLAIN_SECRET");
            given(totpService.verify("PLAIN_SECRET", "654321"))
                    .willReturn(Optional.of(101L));
            given(teamMemberRepository.findTeamIdsByUserIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(List.of());
            given(teamMemberRepository.findManagedTeamIdsByUserIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(List.of());
            given(jwtUtils.generateToken(any(), anyString(), anyString(), any(), any(), any(), any()))
                    .willReturn("access-token");
            given(jwtUtils.getAccessTokenTtl()).willReturn(java.time.Duration.ofMinutes(15));
            given(jwtUtils.getRefreshTokenTtl()).willReturn(java.time.Duration.ofDays(30));
            given(authTokenService.generateRefreshToken(any(), anyString(), any(), notNull()))
                    .willReturn("refresh-token");

            final LoginResponse response =
                    service.verifyMfaToken("raw-mfa-token", "654321");

            assertThat(response.accessToken()).isEqualTo("access-token");
            assertThat(user.getMfaLastUsedTimeStep()).isEqualTo(101L);
        }
    }

    // ── verifyWithBackupCode (backlog #58) ──────────────────────────────

    /**
     * No prior test coverage existed for this method before backlog #58 —
     * added alongside the lockout fix itself.
     */
    @Nested
    @DisplayName("verifyWithBackupCode")
    class VerifyWithBackupCode {

        @Test
        @DisplayName("returns LoginResponse and clears the failure counter on a valid backup code")
        void verifiesAndIssuesTokens() {
            final User user = buildUser(true);
            final AuthToken mfaSessionToken = AuthToken.forTesting(
                    user, TENANT_ID, "hash", AuthToken.Type.MFA_SESSION,
                    Instant.now().plusSeconds(300), null);
            final MfaBackupCode backupCode = MfaBackupCode.create(
                    user, passwordEncoder.encode("aaaa1111"));

            given(authTokenService.peekToken("raw-mfa-token", AuthToken.Type.MFA_SESSION))
                    .willReturn(mfaSessionToken);
            given(bruteForceProtectionService.isLocked(
                    BruteForceProtectionService.Scope.MFA, USER_ID.toString(), TENANT_ID))
                    .willReturn(false);
            given(authTokenService.consumeToken("raw-mfa-token", AuthToken.Type.MFA_SESSION))
                    .willReturn(mfaSessionToken);
            given(backupCodeRepository.findUnusedByUserId(USER_ID))
                    .willReturn(List.of(backupCode));
            given(backupCodeRepository.countUnusedByUserId(USER_ID)).willReturn(0L);
            given(teamMemberRepository.findTeamIdsByUserIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(List.of());
            given(teamMemberRepository.findManagedTeamIdsByUserIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(List.of());
            given(jwtUtils.generateToken(any(), anyString(), anyString(), any(), any(), any(), any()))
                    .willReturn("access-token");
            given(jwtUtils.getAccessTokenTtl()).willReturn(java.time.Duration.ofMinutes(15));
            given(jwtUtils.getRefreshTokenTtl()).willReturn(java.time.Duration.ofDays(30));
            given(authTokenService.generateRefreshToken(any(), anyString(), any(), notNull()))
                    .willReturn("refresh-token");

            final LoginResponse response =
                    service.verifyWithBackupCode("raw-mfa-token", "aaaa1111");

            assertThat(response.accessToken()).isEqualTo("access-token");
            assertThat(backupCode.isUsed()).isTrue();
            then(bruteForceProtectionService).should().recordSuccess(
                    BruteForceProtectionService.Scope.MFA, USER_ID.toString(), TENANT_ID);
        }

        @Test
        @DisplayName("throws 401 and records a failure on an invalid backup code")
        void throws401AndRecordsFailureOnInvalidCode() {
            final User user = buildUser(true);
            final AuthToken mfaSessionToken = AuthToken.forTesting(
                    user, TENANT_ID, "hash", AuthToken.Type.MFA_SESSION,
                    Instant.now().plusSeconds(300), null);

            given(authTokenService.peekToken("raw-mfa-token", AuthToken.Type.MFA_SESSION))
                    .willReturn(mfaSessionToken);
            given(bruteForceProtectionService.isLocked(
                    BruteForceProtectionService.Scope.MFA, USER_ID.toString(), TENANT_ID))
                    .willReturn(false);
            given(authTokenService.consumeToken("raw-mfa-token", AuthToken.Type.MFA_SESSION))
                    .willReturn(mfaSessionToken);
            given(backupCodeRepository.findUnusedByUserId(USER_ID))
                    .willReturn(List.of());

            assertThatThrownBy(() ->
                    service.verifyWithBackupCode("raw-mfa-token", "wrongcode"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);

            then(bruteForceProtectionService).should().recordFailure(
                    BruteForceProtectionService.Scope.MFA, USER_ID.toString(), TENANT_ID);
            assertRefusalAuditedAfterRollback();
        }

        /**
         * The actual regression test for backlog #58, backup-code path.
         * Also verifies the shared-counter design decision documented on
         * this method's own Javadoc: TOTP and backup-code verification
         * intentionally share the same MFA lockout counter, so this test
         * uses the same scope key a TOTP-path lockout would use.
         */
        @Test
        @DisplayName("throws 401 without consuming the token when locked out (backlog #58)")
        void throws401WhenLockedOutWithoutConsumingToken() {
            final User user = buildUser(true);
            final AuthToken mfaSessionToken = AuthToken.forTesting(
                    user, TENANT_ID, "hash", AuthToken.Type.MFA_SESSION,
                    Instant.now().plusSeconds(300), null);

            given(authTokenService.peekToken("raw-mfa-token", AuthToken.Type.MFA_SESSION))
                    .willReturn(mfaSessionToken);
            given(bruteForceProtectionService.isLocked(
                    BruteForceProtectionService.Scope.MFA, USER_ID.toString(), TENANT_ID))
                    .willReturn(true);
            given(bruteForceProtectionService.getRemainingLockout(
                    BruteForceProtectionService.Scope.MFA, USER_ID.toString(), TENANT_ID))
                    .willReturn(java.time.Duration.ofMinutes(5));

            assertThatThrownBy(() ->
                    service.verifyWithBackupCode("raw-mfa-token", "aaaa1111"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);

            then(authTokenService).should(org.mockito.Mockito.never())
                    .consumeToken(anyString(), any());
            then(backupCodeRepository).should(org.mockito.Mockito.never())
                    .findUnusedByUserId(any());
        }
    }

    // ── disableMfa ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("disableMfa")
    class DisableMfa {

        @Test
        @DisplayName("disables MFA after verifying password + TOTP code")
        void disablesMfaAfterVerification() {
            final String rawPassword = "SuperSecret123";
            final User user = buildUserWithMfa(rawPassword);
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(aesEncryptionService.decrypt("encrypted-secret"))
                    .willReturn("PLAIN_SECRET");
            given(totpService.verify("PLAIN_SECRET", "123456")).willReturn(Optional.of(100L));
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.disableMfa(rawPassword, "123456", buildPrincipal());

            assertThat(user.isMfaEnabled()).isFalse();
            assertThat(user.getMfaSecret()).isNull();
            then(backupCodeRepository).should().deleteAllByUserId(USER_ID);
            // Backlog #0-83: the user's sessions stop counting as MFA-verified,
            // and the account's address is told.
            then(authTokenService).should().forgetMfaOfAllSessions(USER_ID, TENANT_ID);
            then(authEmailRequestService).should().requestMfaChangeNotification(user, false);
        }

        @Test
        @DisplayName("throws 401 on wrong password")
        void throws401OnWrongPassword() {
            final User user = buildUserWithMfa("correct-password");
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(Optional.of(user));

            assertThatThrownBy(() ->
                    service.disableMfa("wrong-password", "123456", buildPrincipal()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ── setupMfaWithSetupToken (tenant-required flow) ───────────────────────

    @Nested
    @DisplayName("setupMfaWithSetupToken")
    class SetupMfaWithSetupToken {

        @Test
        @DisplayName("generates secret and QR URL, identifying the user via the setup token")
        void generatesSecretAndQrUrl() {
            final User user = buildUser(false);
            final AuthToken setupToken = AuthToken.forTesting(
                    user, TENANT_ID, "hash", AuthToken.Type.MFA_SETUP_REQUIRED,
                    Instant.now().plusSeconds(600), null);

            given(authTokenService.peekToken("raw-setup-token", AuthToken.Type.MFA_SETUP_REQUIRED))
                    .willReturn(setupToken);
            given(totpService.generateSecret()).willReturn("BASE32SECRET");
            given(aesEncryptionService.encrypt("BASE32SECRET")).willReturn("encrypted");
            given(totpService.generateQrUrl(anyString(), anyString(), anyString()))
                    .willReturn("otpauth://totp/test");
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            final MfaSetupResponse response = service.setupMfaWithSetupToken("raw-setup-token");

            assertThat(response.secret()).isEqualTo("BASE32SECRET");
            assertThat(response.qrUrl()).isEqualTo("otpauth://totp/test");
            assertThat(user.getMfaPendingSecret()).isEqualTo("encrypted");
        }

        @Test
        @DisplayName("does not consume the setup token — may be retried before enable")
        void doesNotConsumeToken() {
            final User user = buildUser(false);
            final AuthToken setupToken = AuthToken.forTesting(
                    user, TENANT_ID, "hash", AuthToken.Type.MFA_SETUP_REQUIRED,
                    Instant.now().plusSeconds(600), null);

            given(authTokenService.peekToken("raw-setup-token", AuthToken.Type.MFA_SETUP_REQUIRED))
                    .willReturn(setupToken);
            given(totpService.generateSecret()).willReturn("BASE32SECRET");
            given(aesEncryptionService.encrypt(anyString())).willReturn("encrypted");
            given(totpService.generateQrUrl(anyString(), anyString(), anyString()))
                    .willReturn("otpauth://totp/test");
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.setupMfaWithSetupToken("raw-setup-token");

            then(authTokenService).should(org.mockito.Mockito.never())
                    .consumeToken(anyString(), any());
        }

        @Test
        @DisplayName("throws 409 when MFA is already enabled")
        void throws409WhenAlreadyEnabled() {
            final User user = buildUser(true);
            final AuthToken setupToken = AuthToken.forTesting(
                    user, TENANT_ID, "hash", AuthToken.Type.MFA_SETUP_REQUIRED,
                    Instant.now().plusSeconds(600), null);

            given(authTokenService.peekToken("raw-setup-token", AuthToken.Type.MFA_SETUP_REQUIRED))
                    .willReturn(setupToken);

            assertThatThrownBy(() -> service.setupMfaWithSetupToken("raw-setup-token"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.CONFLICT);
        }
    }

    // ── enableMfaWithSetupToken (tenant-required flow) ──────────────────────

    @Nested
    @DisplayName("enableMfaWithSetupToken")
    class EnableMfaWithSetupToken {

        @Test
        @DisplayName("enables MFA, consumes the setup token, and completes login with real tokens")
        void enablesMfaAndCompletesLogin() {
            final User user = buildUser(false);
            user.storePendingMfaSecret("encrypted-secret");
            final AuthToken setupToken = AuthToken.forTesting(
                    user, TENANT_ID, "hash", AuthToken.Type.MFA_SETUP_REQUIRED,
                    Instant.now().plusSeconds(600), null);

            given(authTokenService.consumeToken("raw-setup-token", AuthToken.Type.MFA_SETUP_REQUIRED))
                    .willReturn(setupToken);
            given(aesEncryptionService.decrypt("encrypted-secret")).willReturn("PLAIN_SECRET");
            given(totpService.verify("PLAIN_SECRET", "123456")).willReturn(Optional.of(100L));
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));
            given(teamMemberRepository.findTeamIdsByUserIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(List.of());
            given(teamMemberRepository.findManagedTeamIdsByUserIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(List.of());
            given(jwtUtils.generateToken(any(), anyString(), anyString(), any(), any(), any(), any()))
                    .willReturn("access-token");
            given(jwtUtils.getAccessTokenTtl()).willReturn(java.time.Duration.ofMinutes(15));
            given(jwtUtils.getRefreshTokenTtl()).willReturn(java.time.Duration.ofDays(30));
            given(authTokenService.generateRefreshToken(any(), anyString(), any(), notNull()))
                    .willReturn("refresh-token");
            given(totpService.generateBackupCodes())
                    .willReturn(List.of("aaaa1111", "bbbb2222"));

            final MfaEnableWithLoginResponse response =
                    service.enableMfaWithSetupToken("raw-setup-token", "123456");

            assertThat(user.isMfaEnabled()).isTrue();
            assertThat(response.backupCodes()).isNotEmpty();
            assertThat(response.login().accessToken()).isEqualTo("access-token");
            assertThat(response.login().refreshToken()).isEqualTo("refresh-token");
            assertThat(response.login().mfaSetupRequired()).isFalse();
            then(backupCodeRepository).should().saveAll(any());
        }

        @Test
        @DisplayName("throws 401 on invalid TOTP code without consuming backup codes or issuing tokens")
        void throws401OnInvalidCode() {
            final User user = buildUser(false);
            user.storePendingMfaSecret("encrypted-secret");
            final AuthToken setupToken = AuthToken.forTesting(
                    user, TENANT_ID, "hash", AuthToken.Type.MFA_SETUP_REQUIRED,
                    Instant.now().plusSeconds(600), null);

            given(authTokenService.consumeToken("raw-setup-token", AuthToken.Type.MFA_SETUP_REQUIRED))
                    .willReturn(setupToken);
            given(aesEncryptionService.decrypt("encrypted-secret")).willReturn("PLAIN_SECRET");
            given(totpService.verify("PLAIN_SECRET", "000000")).willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.enableMfaWithSetupToken("raw-setup-token", "000000"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);

            then(backupCodeRepository).should(org.mockito.Mockito.never()).saveAll(any());
            then(jwtUtils).should(org.mockito.Mockito.never())
                    .generateToken(any(), anyString(), anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("throws 409 when no pending setup found")
        void throws409WhenNoPendingSetup() {
            final User user = buildUser(false); // no pending secret
            final AuthToken setupToken = AuthToken.forTesting(
                    user, TENANT_ID, "hash", AuthToken.Type.MFA_SETUP_REQUIRED,
                    Instant.now().plusSeconds(600), null);

            given(authTokenService.consumeToken("raw-setup-token", AuthToken.Type.MFA_SETUP_REQUIRED))
                    .willReturn(setupToken);

            assertThatThrownBy(() ->
                    service.enableMfaWithSetupToken("raw-setup-token", "123456"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.CONFLICT);
        }
    }

    // ── TotpService unit tests ────────────────────────────────────────────

    @Nested
    @DisplayName("TotpService — RFC 6238 algorithm")
    class TotpServiceAlgorithm {

        private final TotpService realTotpService = new TotpService("TestIssuer");

        @Test
        @DisplayName("generateSecret returns non-blank base32 string")
        void generateSecretReturnsBase32() {
            final String secret = realTotpService.generateSecret();
            assertThat(secret).isNotBlank();
            // Base32 uses A-Z and 2-7 only
            assertThat(secret).matches("[A-Z2-7]+");
        }

        @Test
        @DisplayName("generated secret is different each call")
        void secretsDiffer() {
            final String s1 = realTotpService.generateSecret();
            final String s2 = realTotpService.generateSecret();
            assertThat(s1).isNotEqualTo(s2);
        }

        @Test
        @DisplayName("verify returns false for obviously wrong code")
        void verifyReturnsFalseForWrongCode() {
            final String secret = realTotpService.generateSecret();
            assertThat(realTotpService.verify(secret, "000000")).isEmpty();
        }

        @Test
        @DisplayName("verify returns false for null code")
        void verifyReturnsFalseForNull() {
            final String secret = realTotpService.generateSecret();
            assertThat(realTotpService.verify(secret, null)).isEmpty();
        }

        @Test
        @DisplayName("verify returns false for wrong length code")
        void verifyReturnsFalseForWrongLength() {
            final String secret = realTotpService.generateSecret();
            assertThat(realTotpService.verify(secret, "12345")).isEmpty();
            assertThat(realTotpService.verify(secret, "1234567")).isEmpty();
        }

        @Test
        @DisplayName("generateQrUrl returns valid otpauth URL")
        void generateQrUrlReturnsValidUrl() {
            final String url = realTotpService.generateQrUrl(
                    "SECRET", "user@test.com", "test-tenant");
            assertThat(url).startsWith("otpauth://totp/");
            assertThat(url).contains("secret=");
            assertThat(url).contains("issuer=");
        }

        @Test
        @DisplayName("generateBackupCodes returns 8 codes of 8 characters each")
        void generateBackupCodes() {
            final List<String> codes = realTotpService.generateBackupCodes();
            assertThat(codes).hasSize(8);
            codes.forEach(code -> assertThat(code).hasSize(8));
        }

        @Test
        @DisplayName("base32 encode-decode round trip")
        void base32RoundTrip() {
            final byte[] original = "HelloWorld".getBytes();
            final String encoded  = TotpService.base32Encode(original);
            final byte[] decoded  = TotpService.base32Decode(encoded);
            assertThat(decoded).isEqualTo(original);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    // ── live session required to enrol (backlog #0-83) ────────────────────

    @Test
    @DisplayName("setup and enable refuse an access token whose session has ended, before touching the user")
    void enrolmentNeedsLiveSession() {
        given(authTokenService.isSessionLive(USER_ID, TENANT_ID, SESSION_ID)).willReturn(false);

        for (final org.assertj.core.api.ThrowableAssert.ThrowingCallable call
                : List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                        () -> service.setupMfa(buildPrincipal()),
                        () -> service.enableMfa("123456", buildPrincipal()))) {
            assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        }
        then(userRepository).shouldHaveNoInteractions();
    }

    // ── resetMfaByAdmin (backlog #0-88) ──────────────────────────────────

    @Nested
    @DisplayName("resetMfaByAdmin")
    class ResetMfaByAdmin {

        private static final UUID ADMIN_SESSION_ID = UUID.randomUUID();

        private UserPrincipal admin() {
            return new UserPrincipal(ADMIN_ID, TENANT_ID, "admin@example.com",
                    List.of("ROLE_ADMIN"), List.of(), List.of(), ADMIN_SESSION_ID);
        }

        private void adminSession(MfaSessionStatusService.Status status) {
            given(mfaSessionStatusService.check(ADMIN_ID, TENANT_ID, ADMIN_SESSION_ID)).willReturn(status);
        }

        private void adminSessionCompletedMfa(boolean completed) {
            adminSession(completed ? MfaSessionStatusService.Status.ACCEPTED : MfaSessionStatusService.Status.NO_MFA);
            if (completed) {
                org.mockito.Mockito.lenient().when(mfaResetRateLimiter.tryConsume(ADMIN_ID, TENANT_ID))
                        .thenReturn(RateLimitDecision.ALLOWED);
            }
        }

        @Test
        @DisplayName("removes factor, pending setup and backup codes, ends every session, tells the user, audits the admin")
        void resetsFactor() {
            final User user = buildUser(true);
            user.storePendingMfaSecret("encrypted-pending");
            adminSessionCompletedMfa(true);
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID)).willReturn(Optional.of(user));
            given(apiKeyService.revokeAllPersonalKeysForUser(USER_ID, TENANT_ID)).willReturn(2);

            service.resetMfaByAdmin(USER_ID, admin());

            then(mfaResetRateLimiter).should().tryConsume(ADMIN_ID, TENANT_ID);
            // Backlog #0-89: a key made with the stolen password goes too.
            then(apiKeyService).should().revokeAllPersonalKeysForUser(USER_ID, TENANT_ID);
            assertThat(user.isMfaEnabled()).isFalse();
            assertThat(user.getMfaSecret()).isNull();
            assertThat(user.getMfaPendingSecret()).isNull();
            then(userRepository).should().save(user);
            then(backupCodeRepository).should().deleteAllByUserId(USER_ID);
            then(authTokenService).should().forgetMfaOfAllSessions(USER_ID, TENANT_ID);
            then(authTokenService).should().invalidateLoginContinuationTokens(USER_ID);
            then(authTokenService).should().invalidateAllRefreshTokens(USER_ID);
            then(authEmailRequestService).should().requestMfaResetNotification(user);
            then(authEmailRequestService).should(org.mockito.Mockito.never())
                    .requestMfaChangeNotification(any(), org.mockito.ArgumentMatchers.anyBoolean());
            then(auditEventPublisher).should().publishAuth(eq(USER_ID), eq(TENANT_ID),
                    eq(AuditEventTypes.MFA_RESET_BY_ADMIN),
                    eq("auth-service"), eq(ADMIN_ID.toString()), anyString(),
                    eq(java.util.Map.of("resetBy", ADMIN_ID.toString(),
                            ApiKeyService.AUDIT_PERSONAL_KEYS_REVOKED, "2")));
        }

        @Test
        @DisplayName("with a time, also revokes every key the user created since, listed in the audit (backlog #0-89)")
        void revokesKeysCreatedSince() {
            final User user = buildUser(true);
            final Instant since = Instant.parse("2026-10-01T00:00:00Z");
            adminSessionCompletedMfa(true);
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID)).willReturn(Optional.of(user));
            final UUID key1 = UUID.randomUUID();
            final UUID key2 = UUID.randomUUID();
            final UUID integration = UUID.randomUUID();
            given(apiKeyService.revokeCreatedBy(USER_ID, TENANT_ID, since, ADMIN_ID)).willReturn(
                    new com.incidentplatform.auth.dto.RevokedApiKeysResponse(List.of(key1, key2), List.of(integration)));

            service.resetMfaByAdmin(USER_ID, admin(), since);

            // The admin is the actor of the INTEGRATION_REVOKED events revokeCreatedBy publishes.
            then(apiKeyService).should().revokeCreatedBy(USER_ID, TENANT_ID, since, ADMIN_ID);
            then(auditEventPublisher).should().publishAuth(eq(USER_ID), eq(TENANT_ID),
                    eq(AuditEventTypes.MFA_RESET_BY_ADMIN),
                    eq("auth-service"), eq(ADMIN_ID.toString()), anyString(),
                    eq(java.util.Map.of("resetBy", ADMIN_ID.toString(),
                            ApiKeyService.AUDIT_PERSONAL_KEYS_REVOKED, "0",
                            MfaService.AUDIT_KEYS_CREATED_SINCE, since.toString(),
                            MfaService.AUDIT_CREATED_KEYS_REVOKED, "2",
                            "keyIds", key1 + "," + key2,
                            "integrationIds", integration.toString())));
        }

        @Test
        @DisplayName("without a time, the keys the user created are kept (only personal keys go)")
        void keepsCreatedKeysByDefault() {
            adminSessionCompletedMfa(true);
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID)).willReturn(Optional.of(buildUser(true)));

            service.resetMfaByAdmin(USER_ID, admin());

            then(apiKeyService).should(org.mockito.Mockito.never()).revokeCreatedBy(any(), any(), any(), any());
        }

        @Test
        @DisplayName("another admin's factor can be reset too")
        void resetsAnotherAdmin() {
            final User otherAdmin = User.forTesting(USER_ID, TENANT_ID, "admin2@example.com",
                    null, true, List.of("ROLE_ADMIN"));
            otherAdmin.storePendingMfaSecret("encrypted-secret");
            otherAdmin.enableMfa();
            adminSessionCompletedMfa(true);
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID)).willReturn(Optional.of(otherAdmin));

            service.resetMfaByAdmin(USER_ID, admin());

            assertThat(otherAdmin.isMfaEnabled()).isFalse();
        }

        @Test
        @DisplayName("403 on one's own account, before anything is read")
        void refusesOwnAccount() {
            final UserPrincipal self = admin();

            assertThatThrownBy(() -> service.resetMfaByAdmin(ADMIN_ID, self))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN));
            then(userRepository).shouldHaveNoInteractions();
            then(mfaSessionStatusService).shouldHaveNoInteractions();
            then(mfaResetRateLimiter).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("403 when the admin's session did not complete MFA (password alone, API key)")
        void refusesWithoutMfaSession() {
            adminSessionCompletedMfa(false);

            assertThatThrownBy(() -> service.resetMfaByAdmin(USER_ID, admin()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN));
            then(userRepository).shouldHaveNoInteractions();
            then(authTokenService).shouldHaveNoInteractions();
            // A refused step-up uses up no budget.
            then(mfaResetRateLimiter).shouldHaveNoInteractions();
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.EnumSource(value = RateLimitDecision.Outcome.class,
                names = "ALLOWED", mode = org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE)
        @DisplayName("a limit that refuses (429) or cannot be checked (503) stops the reset before anything changes")
        void refusedByRateLimit(RateLimitDecision.Outcome outcome) {
            adminSession(MfaSessionStatusService.Status.ACCEPTED);
            final RateLimitDecision refusal =
                    new RateLimitDecision(outcome, 42);
            given(mfaResetRateLimiter.tryConsume(ADMIN_ID, TENANT_ID)).willReturn(refusal);
            final User user = buildUser(true);
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID)).willReturn(Optional.of(user));

            assertThatThrownBy(() -> service.resetMfaByAdmin(USER_ID, admin()))
                    .isInstanceOfSatisfying(RateLimitRefusedException.class,
                            e -> assertThat(e.decision()).isEqualTo(refusal));
            assertThat(user.isMfaEnabled()).isTrue();
            then(userRepository).should(org.mockito.Mockito.never()).save(any());
            then(backupCodeRepository).shouldHaveNoInteractions();
            then(authTokenService).shouldHaveNoInteractions();
            then(authEmailRequestService).shouldHaveNoInteractions();
            then(auditEventPublisher).shouldHaveNoInteractions();
            then(apiKeyService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("a deactivated user can be reset, so a stranger's factor goes before reactivation (review of #0-88)")
        void resetsDeactivatedUser() {
            final User deactivated = User.forTesting(USER_ID, TENANT_ID, "u@example.com", null, false,
                    List.of("ROLE_RESPONDER"));
            deactivated.storePendingMfaSecret("encrypted-secret");
            deactivated.enableMfa();
            adminSessionCompletedMfa(true);
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID)).willReturn(Optional.of(deactivated));

            service.resetMfaByAdmin(USER_ID, admin());

            assertThat(deactivated.isMfaEnabled()).isFalse();
            assertThat(deactivated.isActive()).as("the reset does not reactivate").isFalse();
        }

        @org.junit.jupiter.params.ParameterizedTest
        @org.junit.jupiter.params.provider.EnumSource(value = MfaSessionStatusService.Status.class,
                names = "ACCEPTED", mode = org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE)
        @DisplayName("403 for every way the admin's session fails the platform MFA rule: too old, factor too new (review of #0-88)")
        void refusesEveryFailedStepUp(MfaSessionStatusService.Status status) {
            adminSession(status);

            assertThatThrownBy(() -> service.resetMfaByAdmin(USER_ID, admin()))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(e.getMessage()).isEqualTo(MfaService.stepUpRefusal(status));
                    });
            then(userRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("a too-new factor and an undelivered notice get one message, as on the platform API")
        void stepUpMessages() {
            assertThat(MfaService.stepUpRefusal(MfaSessionStatusService.Status.MFA_ENROLLED_TOO_RECENTLY))
                    .isEqualTo(MfaService.stepUpRefusal(MfaSessionStatusService.Status.MFA_NOTICE_NOT_DELIVERED));
            assertThat(MfaService.stepUpRefusal(MfaSessionStatusService.Status.MFA_TOO_OLD)).contains("recent");
            assertThatThrownBy(() -> MfaService.stepUpRefusal(MfaSessionStatusService.Status.ACCEPTED))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("404 for a user not active in the admin's tenant")
        void notFound() {
            adminSessionCompletedMfa(true);
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.resetMfaByAdmin(USER_ID, admin()))
                    .isInstanceOf(ResourceNotFoundException.class);
            then(authTokenService).shouldHaveNoInteractions();
            then(authEmailRequestService).shouldHaveNoInteractions();
            // A 404 spends no budget (review of #0-88).
            then(mfaResetRateLimiter).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("409 when the user has no factor; nothing ended, nobody emailed")
        void noFactor() {
            adminSessionCompletedMfa(true);
            given(userRepository.findByIdAndTenantId(USER_ID, TENANT_ID)).willReturn(Optional.of(buildUser(false)));

            assertThatThrownBy(() -> service.resetMfaByAdmin(USER_ID, admin()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT));
            then(authTokenService).shouldHaveNoInteractions();
            then(authEmailRequestService).shouldHaveNoInteractions();
            then(auditEventPublisher).shouldHaveNoInteractions();
            then(apiKeyService).shouldHaveNoInteractions();
            // A 409 spends no budget either.
            then(mfaResetRateLimiter).shouldHaveNoInteractions();
        }
    }

    // ── resetMfaBreakGlass (backlog #0-88) ───────────────────────────────

    @Nested
    @DisplayName("resetMfaBreakGlass")
    class ResetMfaBreakGlass {

        private static final String OPERATOR = "platform-operator";
        private static final String ORIGIN = "ops-laptop-user@host-1";

        private User operatorWithMfa() {
            final User user = User.forTesting(USER_ID, OPERATOR, "ops@example.com",
                    null, true, List.of("ROLE_ADMIN"));
            user.storePendingMfaSecret("encrypted-secret");
            user.enableMfa();
            return user;
        }

        @Test
        @DisplayName("resets like the admin reset and audits MFA_RESET_BREAK_GLASS with a confirmed send")
        void resets() {
            final User user = operatorWithMfa();
            given(userRepository.findByEmailAndTenantId("ops@example.com", OPERATOR)).willReturn(Optional.of(user));
            given(apiKeyService.revokeAllPersonalKeysForUser(USER_ID, OPERATOR)).willReturn(1);

            assertThat(service.resetMfaBreakGlass("ops@example.com", " Jane Doe ", " lost phone ", ORIGIN))
                    .isEqualTo(USER_ID);

            then(apiKeyService).should().revokeAllPersonalKeysForUser(USER_ID, OPERATOR);
            assertThat(user.isMfaEnabled()).isFalse();
            then(backupCodeRepository).should().deleteAllByUserId(USER_ID);
            then(authTokenService).should().forgetMfaOfAllSessions(USER_ID, OPERATOR);
            then(authTokenService).should().invalidateLoginContinuationTokens(USER_ID);
            then(authTokenService).should().invalidateAllRefreshTokens(USER_ID);
            then(authEmailRequestService).should().requestMfaResetNotification(user);
            // Backlog #0-84: to the outbox, in the reset's transaction.
            then(auditEventPublisher).should().publishAuth(eq(USER_ID), eq(OPERATOR),
                    eq(AuditEventTypes.MFA_RESET_BREAK_GLASS),
                    eq("auth-service"), eq("break-glass:Jane Doe"), anyString(),
                    eq(java.util.Map.of("resetBy", "break-glass:Jane Doe", "reason", "lost phone",
                            "executedOn", ORIGIN, ApiKeyService.AUDIT_PERSONAL_KEYS_REVOKED, "1")));
        }

        @Test
        @DisplayName("the email is matched as stored after trimming, and a miss explains the exact match")
        void trimsEmailAndExplainsMiss() {
            final User user = operatorWithMfa();
            given(userRepository.findByEmailAndTenantId("ops@example.com", OPERATOR)).willReturn(Optional.of(user));

            assertThat(service.resetMfaBreakGlass("  ops@example.com ", "Jane", "lost phone", ORIGIN))
                    .isEqualTo(USER_ID);
            assertThatThrownBy(() -> service.resetMfaBreakGlass("Ops@Example.com", "Jane", "lost phone", ORIGIN))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("case-sensitive");
        }

        @Test
        @DisplayName("executedOn is required and checked like actor and reason")
        void requiresExecutedOn() {
            for (final String origin : new String[] {null, " ", "user@host\nINFO forged"}) {
                assertThatThrownBy(() -> service.resetMfaBreakGlass("ops@example.com", "Jane", "lost phone", origin)).isInstanceOf(IllegalArgumentException.class);
            }
            then(userRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("ordinary text, accents and other scripts are fine in actor and reason")
        void acceptsOrdinaryText() {
            assertThat(MfaService.isUnsafeInLog('a')).isFalse();
            assertThat("Łukasz Pławiak, zgubiony telefon — потерян".codePoints()
                    .anyMatch(MfaService::isUnsafeInLog)).isFalse();
        }

        @Test
        @DisplayName("only in platform-operator: a customer tenant's user is not found")
        void operatorTenantOnly() {
            given(userRepository.findByEmailAndTenantId("u@example.com", OPERATOR)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.resetMfaBreakGlass("u@example.com", "Jane", "lost phone", ORIGIN))
                    .isInstanceOf(ResourceNotFoundException.class);
            then(authTokenService).shouldHaveNoInteractions();
            then(auditEventPublisher).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("actor and reason are required and bounded, checked before anything is read")
        void requiresActorAndReason() {
            final String tooLong = "x".repeat(MfaService.BREAK_GLASS_REASON_MAX + 1);
            for (final String[] input : new String[][] {
                    {null, "reason"}, {" ", "reason"}, {"Jane", null}, {"Jane", "  "},
                    {"x".repeat(MfaService.BREAK_GLASS_ACTOR_MAX + 1), "reason"}, {"Jane", tooLong},
                    {"Jane\nINFO forged line", "reason"}, {"Jane", "lost\rphone"}, {"Jane", "lost\tphone"},
                    {"Jane\u2028INFO forged", "reason"}, {"Jane", "lost\u2029phone"},
                    {"\u202EenaJ", "reason"}, {"Jane", "lost\u200Bphone"}}) {
                assertThatThrownBy(() -> service.resetMfaBreakGlass("ops@example.com", input[0], input[1], ORIGIN))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            then(userRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("409 for an operator user who is not an admin; nothing changed (review of #0-88)")
        void adminsOnly() {
            final User responder = User.forTesting(USER_ID, OPERATOR, "ops@example.com", null, true,
                    List.of("ROLE_RESPONDER"));
            responder.storePendingMfaSecret("encrypted-secret");
            responder.enableMfa();
            given(userRepository.findByEmailAndTenantId("ops@example.com", OPERATOR)).willReturn(Optional.of(responder));

            assertThatThrownBy(() -> service.resetMfaBreakGlass("ops@example.com", "Jane", "lost phone", ORIGIN))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(responder.isMfaEnabled()).isTrue();
            then(auditEventPublisher).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("409 when the user has no factor; nothing changed or audited")
        void noFactor() {
            final User user = User.forTesting(USER_ID, OPERATOR, "ops@example.com", null, true, List.of("ROLE_ADMIN"));
            given(userRepository.findByEmailAndTenantId("ops@example.com", OPERATOR)).willReturn(Optional.of(user));

            assertThatThrownBy(() -> service.resetMfaBreakGlass("ops@example.com", "Jane", "lost phone", ORIGIN))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT));
            then(authTokenService).shouldHaveNoInteractions();
            then(auditEventPublisher).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("a failure to write the audit event fails the call, so the reset rolls back with it "
                + "(backlog #0-84: the outbox write is in the transaction)")
        void auditWriteFails() {
            given(userRepository.findByEmailAndTenantId("ops@example.com", OPERATOR))
                    .willReturn(Optional.of(operatorWithMfa()));
            org.mockito.BDDMockito.willThrow(new IllegalStateException("outbox write failed"))
                    .given(auditEventPublisher).publishAuth(any(), any(), any(), any(), any(), any(), any());

            assertThatThrownBy(() -> service.resetMfaBreakGlass("ops@example.com", "Jane", "lost phone", ORIGIN))
                    .hasMessage("outbox write failed");
        }
    }

    private User buildUser(boolean mfaEnabled) {
        final User user = User.forTesting(USER_ID, TENANT_ID, "u@example.com",
                null, true, List.of("ROLE_RESPONDER"));
        if (mfaEnabled) {
            user.storePendingMfaSecret("encrypted-secret");
            user.enableMfa();
        }
        return user;
    }

    private User buildUserWithMfa(String rawPassword) {
        final User user = User.forTesting(USER_ID, TENANT_ID, "u@example.com",
                passwordEncoder.encode(rawPassword), true, List.of("ROLE_RESPONDER"));
        user.storePendingMfaSecret("encrypted-secret");
        user.enableMfa();
        return user;
    }

    /** A principal of a live login session (backlog #0-83: enrolling MFA requires one). */
    private UserPrincipal buildPrincipal() {
        return new UserPrincipal(USER_ID, TENANT_ID, "u@example.com",
                List.of("ROLE_RESPONDER"), List.of(), List.of(), SESSION_ID);
    }
}