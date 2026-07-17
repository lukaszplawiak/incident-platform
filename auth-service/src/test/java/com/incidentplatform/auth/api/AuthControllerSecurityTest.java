package com.incidentplatform.auth.api;

import com.incidentplatform.auth.config.SecurityConfig;
import com.incidentplatform.auth.dto.LoginResponse;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.service.*;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.security.ApiKeyAuthFilter;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.access-token-ttl=PT15M",
        "jwt.service-token-ttl=PT1H",
        "jwt.refresh-token-ttl=P30D",
        "spring.application.name=auth-service",
        "security.cors.allowed-origins=http://localhost:4200",
        "mfa.encryption-key=dGVzdC1rZXktMzItYnl0ZXMtZm9yLWRldi1vbmx5ISE="
})
@DisplayName("AuthController")
class AuthControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private AuthService authService;
    @MockitoBean private ForgotPasswordService forgotPasswordService;
    @MockitoBean private PasswordService passwordService;
    @MockitoBean private AuthTokenService authTokenService;
    @MockitoBean private InviteService inviteService;
    @MockitoBean private LogoutService logoutService;
    @MockitoBean private MfaService mfaService;
    @MockitoBean private JwtUtils jwtUtils;
    @MockitoBean private ServiceTokenProvider serviceTokenProvider;
    @MockitoBean private ApiKeyAuthFilter.ApiKeyLookupService ApiKeyLookupService;

    private static final String TENANT_ID = "test-tenant";

    private static LoginResponse buildLoginResponse() {
        return LoginResponse.success(
                "access-token",
                "refresh-token",
                UUID.randomUUID(),
                TENANT_ID,
                "user@example.com",
                List.of("ROLE_ADMIN"),
                Instant.now().plusSeconds(900),
                Instant.now().plusSeconds(86400 * 30));
    }

    // ── public access ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /login")
    class Login {

        @Test
        @DisplayName("200 without Authorization header — login is public")
        void login_noTokenRequired_returns200() throws Exception {
            given(authService.login(any())).willReturn(buildLoginResponse());

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType("application/json")
                            .content("""
                                    {"email":"user@example.com","password":"secret123"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("access-token"))
                    .andExpect(jsonPath("$.refreshToken").value("refresh-token"));
        }

        @Test
        @DisplayName("400 when email missing")
        void missingEmail_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType("application/json")
                            .content("""
                                    {"password":"secret123"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 when email malformed")
        void malformedEmail_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType("application/json")
                            .content("""
                                    {"email":"not-an-email","password":"secret123"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 when password missing")
        void missingPassword_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType("application/json")
                            .content("""
                                    {"email":"user@example.com"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("401 when credentials invalid")
        void invalidCredentials_returns401() throws Exception {
            given(authService.login(any()))
                    .willThrow(new BusinessException(
                            ErrorCodes.UNAUTHORIZED, "Invalid credentials",
                            HttpStatus.UNAUTHORIZED));

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType("application/json")
                            .content("""
                                    {"email":"user@example.com","password":"wrong"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── refresh ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /refresh")
    class Refresh {

        @Test
        @DisplayName("200 with valid refresh token — returns new token pair")
        void validRefreshToken_returns200() throws Exception {
            final User user = User.forTesting(
                    UUID.randomUUID(), TENANT_ID, "user@example.com",
                    "hash", true, List.of("ROLE_ADMIN"));

            final AuthTokenService.RotationResult result =
                    new AuthTokenService.RotationResult(
                            "new-access-token",
                            Instant.now().plusSeconds(900),
                            "new-refresh-token",
                            Instant.now().plusSeconds(86400 * 30),
                            user);

            given(authTokenService.rotateRefreshToken("valid-refresh-token"))
                    .willReturn(result);

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType("application/json")
                            .content("""
                                    {"refreshToken":"valid-refresh-token"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("new-access-token"))
                    .andExpect(jsonPath("$.refreshToken").value("new-refresh-token"));
        }

        @Test
        @DisplayName("401 when refresh token invalid or already used")
        void invalidRefreshToken_returns401() throws Exception {
            given(authTokenService.rotateRefreshToken(anyString()))
                    .willThrow(new BusinessException(
                            ErrorCodes.UNAUTHORIZED, "Token invalid",
                            HttpStatus.UNAUTHORIZED));

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType("application/json")
                            .content("""
                                    {"refreshToken":"bad-token"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("400 when refreshToken is blank")
        void blankRefreshToken_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType("application/json")
                            .content("""
                                    {"refreshToken":""}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("200 without Authorization header — refresh is public")
        void refresh_noTokenRequired_returns200() throws Exception {
            final User user = User.forTesting(
                    UUID.randomUUID(), TENANT_ID, "user@example.com",
                    "hash", true, List.of("ROLE_ADMIN"));

            given(authTokenService.rotateRefreshToken("valid-token"))
                    .willReturn(new AuthTokenService.RotationResult(
                            "access", Instant.now().plusSeconds(900),
                            "refresh", Instant.now().plusSeconds(86400 * 30),
                            user));

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType("application/json")
                            .content("""
                                    {"refreshToken":"valid-token"}
                                    """))
                    .andExpect(status().isOk());
        }
    }

    // ── accept-invite ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /accept-invite")
    class AcceptInvite {

        @Test
        @DisplayName("204 without Authorization header — accept-invite is public")
        void acceptInvite_publicEndpoint_returns204() throws Exception {
            mockMvc.perform(post("/api/v1/auth/accept-invite")
                            .contentType("application/json")
                            .content("""
                                    {"token":"raw-token","password":"SuperSecret123!"}
                                    """))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("400 when password too short")
        void acceptInvite_shortPassword_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/accept-invite")
                            .contentType("application/json")
                            .content("""
                                    {"token":"raw-token","password":"short"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 when token blank")
        void acceptInvite_blankToken_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/accept-invite")
                            .contentType("application/json")
                            .content("""
                                    {"token":"","password":"SuperSecret123!"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("401 when token invalid or expired")
        void acceptInvite_invalidToken_returns401() throws Exception {
            willThrow(new BusinessException(
                    ErrorCodes.UNAUTHORIZED,
                    "Token is invalid, expired, or already used",
                    HttpStatus.UNAUTHORIZED))
                    .given(inviteService).acceptInvite(any());

            mockMvc.perform(post("/api/v1/auth/accept-invite")
                            .contentType("application/json")
                            .content("""
                                    {"token":"expired-token","password":"SuperSecret123!"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /forgot-password")
    class ForgotPassword {

        @Test
        @DisplayName("202 for existing email — user enumeration protection")
        void existingEmail_returns202() throws Exception {
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                            .header("X-Tenant-Id", TENANT_ID)
                            .contentType("application/json")
                            .content("""
                                    {"email":"user@example.com"}
                                    """))
                    .andExpect(status().isAccepted());
        }

        @Test
        @DisplayName("202 for non-existing email — SAME response, user enumeration protection")
        void nonExistingEmail_returns202() throws Exception {
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                            .header("X-Tenant-Id", TENANT_ID)
                            .contentType("application/json")
                            .content("""
                                    {"email":"nobody@example.com"}
                                    """))
                    .andExpect(status().isAccepted());
        }

        @Test
        @DisplayName("400 when email is blank")
        void blankEmail_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                            .header("X-Tenant-Id", TENANT_ID)
                            .contentType("application/json")
                            .content("""
                                    {"email":""}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 when email is malformed")
        void malformedEmail_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                            .header("X-Tenant-Id", TENANT_ID)
                            .contentType("application/json")
                            .content("""
                                    {"email":"not-an-email"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("202 without Authorization header — endpoint is public")
        void noAuth_returns202() throws Exception {
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                            .header("X-Tenant-Id", TENANT_ID)
                            .contentType("application/json")
                            .content("""
                                    {"email":"user@example.com"}
                                    """))
                    .andExpect(status().isAccepted());
        }
    }

    @Nested
    @DisplayName("POST /reset-password")
    class ResetPassword {

        @Test
        @DisplayName("204 with valid token and new password")
        void validRequest_returns204() throws Exception {
            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType("application/json")
                            .content("""
                                    {"token":"valid-token","newPassword":"NewSecure123!"}
                                    """))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("401 when token invalid or expired")
        void invalidToken_returns401() throws Exception {
            org.mockito.BDDMockito.willThrow(
                            new com.incidentplatform.shared.exception.BusinessException(
                                    com.incidentplatform.shared.exception.ErrorCodes.UNAUTHORIZED,
                                    "Token invalid",
                                    org.springframework.http.HttpStatus.UNAUTHORIZED))
                    .given(passwordService).resetPassword(any());

            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType("application/json")
                            .content("""
                                    {"token":"expired-token","newPassword":"NewSecure123!"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("400 when newPassword too short")
        void shortPassword_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType("application/json")
                            .content("""
                                    {"token":"valid-token","newPassword":"short"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 when token is blank")
        void blankToken_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType("application/json")
                            .content("""
                                    {"token":"","newPassword":"NewSecure123!"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("204 without Authorization header — endpoint is public")
        void noAuth_returns204() throws Exception {
            mockMvc.perform(post("/api/v1/auth/reset-password")
                            .contentType("application/json")
                            .content("""
                                    {"token":"valid-token","newPassword":"NewSecure123!"}
                                    """))
                    .andExpect(status().isNoContent());
        }
    }

}