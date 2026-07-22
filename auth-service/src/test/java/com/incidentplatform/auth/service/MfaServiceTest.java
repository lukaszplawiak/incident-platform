package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.MfaBackupCode;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.MfaEnableResponse;
import com.incidentplatform.auth.dto.MfaEnableWithLoginResponse;
import com.incidentplatform.auth.dto.MfaSetupResponse;
import com.incidentplatform.auth.repository.MfaBackupCodeRepository;
import com.incidentplatform.auth.repository.TeamMemberRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.exception.BusinessException;
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

    private final PasswordEncoder passwordEncoder =
            Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    private MfaService service;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID   USER_ID   = UUID.randomUUID();
    private static final UUID   ADMIN_ID  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MfaService(
                userRepository, backupCodeRepository, authTokenService,
                teamMemberRepository, totpService, aesEncryptionService,
                passwordEncoder, jwtUtils, auditEventPublisher);
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
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
            given(totpService.verify("PLAIN_SECRET", "123456")).willReturn(true);
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
            given(totpService.verify("PLAIN_SECRET", "999999")).willReturn(false);

            assertThatThrownBy(() -> service.enableMfa("999999", buildPrincipal()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
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
            given(totpService.verify("PLAIN_SECRET", "123456")).willReturn(true);
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.disableMfa(rawPassword, "123456", buildPrincipal());

            assertThat(user.isMfaEnabled()).isFalse();
            assertThat(user.getMfaSecret()).isNull();
            then(backupCodeRepository).should().deleteAllByUserId(USER_ID);
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
            given(totpService.verify("PLAIN_SECRET", "123456")).willReturn(true);
            given(userRepository.save(any())).willAnswer(i -> i.getArgument(0));
            given(teamMemberRepository.findTeamIdsByUserIdAndTenantId(USER_ID, TENANT_ID))
                    .willReturn(List.of());
            given(jwtUtils.generateToken(any(), anyString(), anyString(), any(), any()))
                    .willReturn("access-token");
            given(jwtUtils.getAccessTokenTtl()).willReturn(java.time.Duration.ofMinutes(15));
            given(jwtUtils.getRefreshTokenTtl()).willReturn(java.time.Duration.ofDays(30));
            given(authTokenService.generateRefreshToken(any(), anyString()))
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
            given(totpService.verify("PLAIN_SECRET", "000000")).willReturn(false);

            assertThatThrownBy(() ->
                    service.enableMfaWithSetupToken("raw-setup-token", "000000"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getHttpStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);

            then(backupCodeRepository).should(org.mockito.Mockito.never()).saveAll(any());
            then(jwtUtils).should(org.mockito.Mockito.never())
                    .generateToken(any(), anyString(), anyString(), any(), any());
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
            assertThat(realTotpService.verify(secret, "000000")).isFalse();
        }

        @Test
        @DisplayName("verify returns false for null code")
        void verifyReturnsFalseForNull() {
            final String secret = realTotpService.generateSecret();
            assertThat(realTotpService.verify(secret, null)).isFalse();
        }

        @Test
        @DisplayName("verify returns false for wrong length code")
        void verifyReturnsFalseForWrongLength() {
            final String secret = realTotpService.generateSecret();
            assertThat(realTotpService.verify(secret, "12345")).isFalse();
            assertThat(realTotpService.verify(secret, "1234567")).isFalse();
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

    private UserPrincipal buildPrincipal() {
        return new UserPrincipal(USER_ID, TENANT_ID, "u@example.com",
                List.of("ROLE_RESPONDER"), List.of());
    }
}