package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.LoginRequest;
import com.incidentplatform.auth.dto.LoginResponse;
import com.incidentplatform.auth.ratelimit.LoginAttemptService;
import com.incidentplatform.auth.repository.TeamMemberRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private AuthTokenService authTokenService;

    @Mock
    private LoginAttemptService loginAttemptService;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @Mock private TenantSettingsService tenantSettingsService;

    private AuthService authService;

    private static final String TENANT_ID = "test-tenant";
    private static final String EMAIL = "admin@example.com";
    private static final String RAW_PASSWORD = "SuperSecret123";
    private static final PasswordEncoder ENCODER =
            Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository, jwtUtils, loginAttemptService,
                authTokenService, ENCODER,
                auditEventPublisher, teamMemberRepository,
                tenantSettingsService);
        TenantContext.set(TENANT_ID);
        // Default: not locked
        given(loginAttemptService.isLocked(any(), any())).willReturn(false);
        // lenient — not all tests reach this (some fail before MFA check)
        lenient().when(tenantSettingsService.isMfaRequired(any())).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── successful login ─────────────────────────────────────────────────

    @Nested
    @DisplayName("login — success")
    class LoginSuccess {

        @Test
        @DisplayName("returns LoginResponse with token and user details")
        void returnsLoginResponse() {
            final User user = User.forTesting(
                    UUID.randomUUID(), TENANT_ID, EMAIL,
                    ENCODER.encode(RAW_PASSWORD), true, List.of("ROLE_ADMIN"));

            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(jwtUtils.generateToken(eq(user.getId()), eq(TENANT_ID),
                    eq(EMAIL), anyList(), any())).willReturn("jwt-token");
            given(jwtUtils.getAccessTokenTtl()).willReturn(Duration.ofMinutes(15));
            given(teamMemberRepository.findTeamIdsByUserIdAndTenantId(
                    any(), anyString())).willReturn(List.of());
            given(jwtUtils.getRefreshTokenTtl()).willReturn(Duration.ofDays(30));
            given(authTokenService.generateRefreshToken(any(), anyString()))
                    .willReturn("raw-refresh-token");

            final LoginResponse response =
                    authService.login(new LoginRequest(EMAIL, RAW_PASSWORD));

            assertThat(response.accessToken()).isEqualTo("jwt-token");
            assertThat(response.refreshToken()).isEqualTo("raw-refresh-token");
            assertThat(response.email()).isEqualTo(EMAIL);
            assertThat(response.roles()).containsExactly("ROLE_ADMIN");
            assertThat(response.accessExpiresAt()).isAfter(Instant.now());
            assertThat(response.refreshExpiresAt()).isAfter(Instant.now());
            assertThat(response.mfaRequired()).isFalse();
            assertThat(response.mfaToken()).isNull();
        }

        @Test
        @DisplayName("clears failure counter on successful login")
        void clearsFailureCounterOnSuccess() {
            final User user = User.forTesting(
                    UUID.randomUUID(), TENANT_ID, EMAIL,
                    ENCODER.encode(RAW_PASSWORD), true, List.of("ROLE_ADMIN"));

            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(jwtUtils.generateToken(any(), any(), any(), any(), any()))
                    .willReturn("token");
            given(jwtUtils.getAccessTokenTtl()).willReturn(Duration.ofMinutes(15));
            given(teamMemberRepository.findTeamIdsByUserIdAndTenantId(
                    any(), anyString())).willReturn(List.of());
            given(jwtUtils.getRefreshTokenTtl()).willReturn(Duration.ofDays(30));
            given(authTokenService.generateRefreshToken(any(), anyString()))
                    .willReturn("raw-refresh-token");

            authService.login(new LoginRequest(EMAIL, RAW_PASSWORD));

            then(loginAttemptService).should().recordSuccess(EMAIL, TENANT_ID);
        }
    }

    // ── lockout ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("login — account locked")
    class AccountLocked {

        @Test
        @DisplayName("throws 401 immediately when account is locked")
        void throws401WhenLocked() {
            given(loginAttemptService.isLocked(EMAIL, TENANT_ID)).willReturn(true);
            given(loginAttemptService.getRemainingLockout(EMAIL, TENANT_ID))
                    .willReturn(Duration.ofMinutes(14));

            assertThatThrownBy(() ->
                    authService.login(new LoginRequest(EMAIL, RAW_PASSWORD)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getHttpStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("does not query DB when account is locked")
        void doesNotQueryDbWhenLocked() {
            given(loginAttemptService.isLocked(EMAIL, TENANT_ID)).willReturn(true);
            given(loginAttemptService.getRemainingLockout(any(), any()))
                    .willReturn(Duration.ofMinutes(1));

            assertThatThrownBy(() ->
                    authService.login(new LoginRequest(EMAIL, RAW_PASSWORD)))
                    .isInstanceOf(BusinessException.class);

            then(userRepository).shouldHaveNoInteractions();
        }
    }

    // ── failure recording ─────────────────────────────────────────────────

    @Nested
    @DisplayName("login — failure recording")
    class FailureRecording {

        @Test
        @DisplayName("records failure when user not found")
        void recordsFailureOnUserNotFound() {
            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    authService.login(new LoginRequest(EMAIL, RAW_PASSWORD)))
                    .isInstanceOf(BusinessException.class);

            then(loginAttemptService).should().recordFailure(EMAIL, TENANT_ID);
        }

        @Test
        @DisplayName("records failure on wrong password")
        void recordsFailureOnWrongPassword() {
            final User user = User.forTesting(
                    UUID.randomUUID(), TENANT_ID, EMAIL,
                    ENCODER.encode(RAW_PASSWORD), true, List.of("ROLE_ADMIN"));

            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                    .willReturn(Optional.of(user));

            assertThatThrownBy(() ->
                    authService.login(new LoginRequest(EMAIL, "WrongPass")))
                    .isInstanceOf(BusinessException.class);

            then(loginAttemptService).should().recordFailure(EMAIL, TENANT_ID);
        }

        @Test
        @DisplayName("does not call JwtUtils when credentials are invalid")
        void doesNotGenerateTokenOnFailure() {
            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    authService.login(new LoginRequest(EMAIL, RAW_PASSWORD)))
                    .isInstanceOf(BusinessException.class);

            Mockito.verifyNoInteractions(jwtUtils);
        }
    }

    // ── MFA required ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("login — MFA required")
    class LoginMfaRequired {

        @Test
        @DisplayName("returns mfaRequired=true and mfaToken when user has MFA enabled")
        void returnsMfaTokenWhenMfaEnabled() {
            final User user = User.forTesting(
                    UUID.randomUUID(), TENANT_ID, EMAIL,
                    ENCODER.encode(RAW_PASSWORD), true, List.of("ROLE_ADMIN"));
            user.storePendingMfaSecret("encrypted-secret");
            user.enableMfa();

            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(authTokenService.generateMfaSessionToken(any(), anyString()))
                    .willReturn("raw-mfa-token");

            final LoginResponse response =
                    authService.login(new LoginRequest(EMAIL, RAW_PASSWORD));

            assertThat(response.mfaRequired()).isTrue();
            assertThat(response.mfaToken()).isEqualTo("raw-mfa-token");
            assertThat(response.accessToken()).isNull();
            assertThat(response.refreshToken()).isNull();
        }

        @Test
        @DisplayName("throws 403 MFA_SETUP_REQUIRED when tenant requires MFA but user not configured")
        void throws403WhenTenantRequiresMfaAndUserNotConfigured() {
            final User user = User.forTesting(
                    UUID.randomUUID(), TENANT_ID, EMAIL,
                    ENCODER.encode(RAW_PASSWORD), true, List.of("ROLE_ADMIN"));

            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                    .willReturn(Optional.of(user));
            lenient().when(tenantSettingsService.isMfaRequired(TENANT_ID)).thenReturn(true);

            assertThatThrownBy(() ->
                    authService.login(new LoginRequest(EMAIL, RAW_PASSWORD)))
                    .isInstanceOf(com.incidentplatform.shared.exception.BusinessException.class)
                    .hasMessageContaining("MFA");
        }
    }


}