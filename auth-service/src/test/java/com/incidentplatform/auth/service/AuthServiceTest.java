package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.LoginRequest;
import com.incidentplatform.auth.dto.LoginResponse;
import com.incidentplatform.auth.repository.UserRepository;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.incidentplatform.shared.exception.BusinessException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtUtils jwtUtils;

    private AuthService authService;

    private static final String TENANT_ID = "test-tenant";
    private static final String EMAIL = "admin@example.com";
    private static final String RAW_PASSWORD = "SuperSecret123";
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, jwtUtils);
        TenantContext.set(TENANT_ID);
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
                    ENCODER.encode(RAW_PASSWORD), true,
                    List.of("ROLE_ADMIN"));

            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(jwtUtils.generateToken(eq(user.getId()), eq(TENANT_ID),
                    eq(EMAIL), anyList()))
                    .willReturn("jwt-token-value");
            given(jwtUtils.getServiceExpirationMs()).willReturn(3_600_000L);

            final LoginResponse response =
                    authService.login(new LoginRequest(EMAIL, RAW_PASSWORD));

            assertThat(response.token()).isEqualTo("jwt-token-value");
            assertThat(response.userId()).isEqualTo(user.getId());
            assertThat(response.tenantId()).isEqualTo(TENANT_ID);
            assertThat(response.email()).isEqualTo(EMAIL);
            assertThat(response.roles()).containsExactly("ROLE_ADMIN");
            assertThat(response.expiresAt()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("passes all user roles to JwtUtils.generateToken")
        void passesAllRolesToJwt() {
            final User user = User.forTesting(
                    UUID.randomUUID(), TENANT_ID, EMAIL,
                    ENCODER.encode(RAW_PASSWORD), true,
                    List.of("ROLE_ADMIN", "ROLE_RESPONDER"));

            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                    .willReturn(Optional.of(user));
            given(jwtUtils.generateToken(any(), any(), any(),
                    eq(List.of("ROLE_ADMIN", "ROLE_RESPONDER"))))
                    .willReturn("jwt-token");
            given(jwtUtils.getServiceExpirationMs()).willReturn(3_600_000L);

            final LoginResponse response =
                    authService.login(new LoginRequest(EMAIL, RAW_PASSWORD));

            assertThat(response.roles())
                    .containsExactly("ROLE_ADMIN", "ROLE_RESPONDER");
        }
    }

    // ── failed login — same 401 message for all cases ──────────────────────

    @Nested
    @DisplayName("login — failure (401, identical message — no enumeration)")
    class LoginFailure {

        @Test
        @DisplayName("user not found — 401 Invalid credentials")
        void userNotFound_returns401() {
            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    authService.login(new LoginRequest(EMAIL, RAW_PASSWORD)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Invalid credentials")
                    .extracting(ex -> ((BusinessException) ex).getHttpStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("inactive user — 401 Invalid credentials")
        void inactiveUser_returns401() {
            final User user = User.forTesting(
                    UUID.randomUUID(), TENANT_ID, EMAIL,
                    ENCODER.encode(RAW_PASSWORD), false,
                    List.of("ROLE_ADMIN"));

            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                    .willReturn(Optional.of(user));

            assertThatThrownBy(() ->
                    authService.login(new LoginRequest(EMAIL, RAW_PASSWORD)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Invalid credentials");
        }

        @Test
        @DisplayName("OAuth2-only user (null password hash) — 401 Invalid credentials")
        void oauth2OnlyUser_returns401() {
            final User user = User.forTesting(
                    UUID.randomUUID(), TENANT_ID, EMAIL,
                    null, true, List.of("ROLE_ADMIN"));

            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                    .willReturn(Optional.of(user));

            assertThatThrownBy(() ->
                    authService.login(new LoginRequest(EMAIL, RAW_PASSWORD)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Invalid credentials");
        }

        @Test
        @DisplayName("wrong password — 401 Invalid credentials")
        void wrongPassword_returns401() {
            final User user = User.forTesting(
                    UUID.randomUUID(), TENANT_ID, EMAIL,
                    ENCODER.encode(RAW_PASSWORD), true,
                    List.of("ROLE_ADMIN"));

            given(userRepository.findByEmailAndTenantId(EMAIL, TENANT_ID))
                    .willReturn(Optional.of(user));

            assertThatThrownBy(() ->
                    authService.login(new LoginRequest(EMAIL, "WrongPassword")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Invalid credentials");
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

    // ── tenant resolution ────────────────────────────────────────────────

    @Nested
    @DisplayName("tenant resolution")
    class TenantResolution {

        @Test
        @DisplayName("resolves tenant from TenantContext, not from request body")
        void resolvesTenantFromContext() {
            TenantContext.set("other-tenant");
            final User user = User.forTesting(
                    UUID.randomUUID(), "other-tenant", EMAIL,
                    ENCODER.encode(RAW_PASSWORD), true,
                    List.of("ROLE_ADMIN"));

            given(userRepository.findByEmailAndTenantId(EMAIL, "other-tenant"))
                    .willReturn(Optional.of(user));
            given(jwtUtils.generateToken(any(), any(), any(), any()))
                    .willReturn("token");
            given(jwtUtils.getServiceExpirationMs()).willReturn(3_600_000L);

            final LoginResponse response =
                    authService.login(new LoginRequest(EMAIL, RAW_PASSWORD));

            assertThat(response.tenantId()).isEqualTo("other-tenant");
        }
    }
}