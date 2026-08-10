package com.incidentplatform.auth.api;

import com.incidentplatform.auth.config.SecurityConfig;
import com.incidentplatform.auth.dto.LoginResponse;
import com.incidentplatform.auth.dto.MfaBackupCodesStatusResponse;
import com.incidentplatform.auth.dto.MfaEnableResponse;
import com.incidentplatform.auth.dto.MfaSetupResponse;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.service.*;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.security.ApiKeyAuthFilter;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.ServiceTokenProvider;
import com.incidentplatform.shared.security.UnauthorizedEntryPoint;
import com.incidentplatform.shared.security.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    private static final UUID USER_ID = UUID.randomUUID();

    /**
     * Builds a real {@link UserPrincipal}-typed authentication for one or
     * more roles, applied to a single {@code mockMvc.perform(...)} call via
     * {@code .with(principal("ROLE_RESPONDER"))}.
     *
     * <p>Same pattern established in {@code IncidentControllerSecurityTest}
     * — see that class's Javadoc ("Why NOT plain {@code @WithMockUser}")
     * for the full rationale: {@code @WithMockUser} authenticates as a
     * generic Spring Security {@code User}, not this app's
     * {@link UserPrincipal} record, so {@code @AuthenticationPrincipal
     * UserPrincipal principal} silently resolves to {@code null}
     * (Spring's default {@code errorOnInvalidType=false}). For the
     * endpoints added below (logout, MFA setup/enable/disable,
     * backup-codes status), that null principal would simply be forwarded
     * into an already-mocked service call — no crash, but the test would
     * give false confidence: it wouldn't actually verify the correct
     * principal reaches the service. Using a real UserPrincipal-typed
     * Authentication makes it possible to assert on the actual argument
     * (via {@code eq(principal)}), not just {@code any()}.
     */
    private static RequestPostProcessor principal(String... roles) {
        final UserPrincipal userPrincipal = new UserPrincipal(
                USER_ID, TENANT_ID, "user@example.com", List.of(roles), List.of());
        final List<GrantedAuthority> authorities = List.of(roles).stream()
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
        return authentication(new UsernamePasswordAuthenticationToken(
                userPrincipal, null, authorities));
    }

    private static UserPrincipal buildPrincipal(String... roles) {
        return new UserPrincipal(
                USER_ID, TENANT_ID, "user@example.com", List.of(roles), List.of());
    }

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
            given(authService.login(any(), any())).willReturn(buildLoginResponse());

            mockMvc.perform(post("/api/v1/auth/login")
                            .header("X-Tenant-Id", TENANT_ID)
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
                            .header("X-Tenant-Id", TENANT_ID)
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
                            .header("X-Tenant-Id", TENANT_ID)
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
                            .header("X-Tenant-Id", TENANT_ID)
                            .contentType("application/json")
                            .content("""
                                    {"email":"user@example.com"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("401 when credentials invalid")
        void invalidCredentials_returns401() throws Exception {
            given(authService.login(any(), any()))
                    .willThrow(new BusinessException(
                            ErrorCodes.UNAUTHORIZED, "Invalid credentials",
                            HttpStatus.UNAUTHORIZED));

            mockMvc.perform(post("/api/v1/auth/login")
                            .header("X-Tenant-Id", TENANT_ID)
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
                    .given(passwordService).resetPassword(any(), any());

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
    @Nested
    @DisplayName("POST /mfa/setup-required")
    class MfaSetupRequired {

        @Test
        @DisplayName("200 without Authorization header — public, identified by mfaSetupToken")
        void noAuth_returns200() throws Exception {
            given(mfaService.setupMfaWithSetupToken(anyString()))
                    .willReturn(new com.incidentplatform.auth.dto.MfaSetupResponse(
                            "otpauth://totp/test", "BASE32SECRET"));

            mockMvc.perform(post("/api/v1/auth/mfa/setup-required")
                            .contentType("application/json")
                            .content("""
                                    {"mfaSetupToken":"raw-setup-token"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.secret").value("BASE32SECRET"));
        }

        @Test
        @DisplayName("401 when setup token invalid or expired")
        void invalidToken_returns401() throws Exception {
            willThrow(new BusinessException(
                    ErrorCodes.UNAUTHORIZED, "Invalid or expired token", HttpStatus.UNAUTHORIZED))
                    .given(mfaService).setupMfaWithSetupToken(anyString());

            mockMvc.perform(post("/api/v1/auth/mfa/setup-required")
                            .contentType("application/json")
                            .content("""
                                    {"mfaSetupToken":"expired-token"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("400 when mfaSetupToken is blank")
        void blankToken_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/mfa/setup-required")
                            .contentType("application/json")
                            .content("""
                                    {"mfaSetupToken":""}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /mfa/enable-required")
    class MfaEnableRequired {

        @Test
        @DisplayName("200 without Authorization header — public, completes login")
        void noAuth_returns200() throws Exception {
            given(mfaService.enableMfaWithSetupToken(anyString(), anyString()))
                    .willReturn(new com.incidentplatform.auth.dto.MfaEnableWithLoginResponse(
                            List.of("aaaa1111", "bbbb2222"), buildLoginResponse()));

            mockMvc.perform(post("/api/v1/auth/mfa/enable-required")
                            .contentType("application/json")
                            .content("""
                                    {"mfaSetupToken":"raw-setup-token","totpCode":"123456"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.backupCodes").isArray())
                    .andExpect(jsonPath("$.login.accessToken").value("access-token"));
        }

        @Test
        @DisplayName("401 when TOTP code invalid")
        void invalidCode_returns401() throws Exception {
            willThrow(new BusinessException(
                    ErrorCodes.UNAUTHORIZED, "Invalid TOTP code", HttpStatus.UNAUTHORIZED))
                    .given(mfaService).enableMfaWithSetupToken(anyString(), anyString());

            mockMvc.perform(post("/api/v1/auth/mfa/enable-required")
                            .contentType("application/json")
                            .content("""
                                    {"mfaSetupToken":"raw-setup-token","totpCode":"000000"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("400 when totpCode is not 6 digits")
        void malformedCode_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/mfa/enable-required")
                            .contentType("application/json")
                            .content("""
                                    {"mfaSetupToken":"raw-setup-token","totpCode":"12"}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── mfa/verify (public — mfa session token, not Bearer) ─────────────────

    @Nested
    @DisplayName("POST /mfa/verify")
    class MfaVerify {

        @Test
        @DisplayName("200 without Authorization header — public, identified by mfaToken")
        void noAuth_returns200() throws Exception {
            given(mfaService.verifyMfaToken("raw-mfa-token", "123456"))
                    .willReturn(buildLoginResponse());

            mockMvc.perform(post("/api/v1/auth/mfa/verify")
                            .contentType("application/json")
                            .content("""
                                    {"mfaToken":"raw-mfa-token","totpCode":"123456"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("access-token"));
        }

        @Test
        @DisplayName("401 when TOTP code invalid or mfaToken expired/used")
        void invalidCode_returns401() throws Exception {
            willThrow(new BusinessException(
                    ErrorCodes.UNAUTHORIZED, "Invalid TOTP code", HttpStatus.UNAUTHORIZED))
                    .given(mfaService).verifyMfaToken(anyString(), anyString());

            mockMvc.perform(post("/api/v1/auth/mfa/verify")
                            .contentType("application/json")
                            .content("""
                                    {"mfaToken":"raw-mfa-token","totpCode":"000000"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("400 when totpCode is not 6 digits")
        void malformedCode_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/mfa/verify")
                            .contentType("application/json")
                            .content("""
                                    {"mfaToken":"raw-mfa-token","totpCode":"12"}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── mfa/verify-backup (public — mfa session token, not Bearer) ──────────

    @Nested
    @DisplayName("POST /mfa/verify-backup")
    class MfaVerifyBackup {

        @Test
        @DisplayName("200 without Authorization header — public, identified by mfaToken")
        void noAuth_returns200() throws Exception {
            given(mfaService.verifyWithBackupCode("raw-mfa-token", "aaaa1111"))
                    .willReturn(buildLoginResponse());

            mockMvc.perform(post("/api/v1/auth/mfa/verify-backup")
                            .contentType("application/json")
                            .content("""
                                    {"mfaToken":"raw-mfa-token","backupCode":"aaaa1111"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("access-token"));
        }

        @Test
        @DisplayName("401 when backup code invalid, already used, or mfaToken expired")
        void invalidCode_returns401() throws Exception {
            willThrow(new BusinessException(
                    ErrorCodes.UNAUTHORIZED, "Invalid or already-used backup code",
                    HttpStatus.UNAUTHORIZED))
                    .given(mfaService).verifyWithBackupCode(anyString(), anyString());

            mockMvc.perform(post("/api/v1/auth/mfa/verify-backup")
                            .contentType("application/json")
                            .content("""
                                    {"mfaToken":"raw-mfa-token","backupCode":"aaaa1111"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("400 when backupCode is not 8 characters")
        void malformedCode_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/mfa/verify-backup")
                            .contentType("application/json")
                            .content("""
                                    {"mfaToken":"raw-mfa-token","backupCode":"short"}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── logout (Bearer JWT — previously completely untested) ────────────────

    @Nested
    @DisplayName("POST /logout")
    class Logout {

        @Test
        @DisplayName("401 unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/auth/logout"))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * Regression test for the fix documented in {@link #principal}'s
         * Javadoc: verifies the EXACT principal and raw token reach
         * LogoutService, not just that some call happened — a plain
         * {@code @WithMockUser} test could only ever assert with
         * {@code any()} here, since its principal is silently null.
         */
        @Test
        @DisplayName("204 authenticated — passes the correct principal and raw token to LogoutService")
        void authenticated_returns204() throws Exception {
            mockMvc.perform(post("/api/v1/auth/logout")
                            .header("Authorization", "Bearer raw-access-token")
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isNoContent());

            then(logoutService).should().logout(
                    eq("raw-access-token"), eq(buildPrincipal("ROLE_RESPONDER")));
        }

        @Test
        @DisplayName("401 when Authorization header is present but not a Bearer token")
        void nonBearerHeader_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/auth/logout")
                            .header("Authorization", "Basic dXNlcjpwYXNz")
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── mfa/setup (Bearer JWT — previously completely untested) ─────────────

    @Nested
    @DisplayName("POST /mfa/setup")
    class MfaSetup {

        @Test
        @DisplayName("401 unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/auth/mfa/setup"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("200 authenticated — passes the correct principal to MfaService")
        void authenticated_returns200() throws Exception {
            given(mfaService.setupMfa(eq(buildPrincipal("ROLE_RESPONDER"))))
                    .willReturn(new MfaSetupResponse("otpauth://totp/test", "BASE32SECRET"));

            mockMvc.perform(post("/api/v1/auth/mfa/setup")
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.secret").value("BASE32SECRET"));
        }

        @Test
        @DisplayName("409 when MFA already enabled")
        void alreadyEnabled_returns409() throws Exception {
            willThrow(new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION, "MFA already enabled", HttpStatus.CONFLICT))
                    .given(mfaService).setupMfa(any());

            mockMvc.perform(post("/api/v1/auth/mfa/setup")
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isConflict());
        }
    }

    // ── mfa/enable (Bearer JWT — previously completely untested) ────────────

    @Nested
    @DisplayName("POST /mfa/enable")
    class MfaEnable {

        @Test
        @DisplayName("401 unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/auth/mfa/enable")
                            .contentType("application/json")
                            .content("""
                                    {"totpCode":"123456"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("200 authenticated — passes the correct principal and totpCode to MfaService")
        void authenticated_returns200() throws Exception {
            given(mfaService.enableMfa(
                    eq("123456"), eq(buildPrincipal("ROLE_RESPONDER"))))
                    .willReturn(MfaEnableResponse.of(List.of("aaaa1111", "bbbb2222")));

            mockMvc.perform(post("/api/v1/auth/mfa/enable")
                            .with(principal("ROLE_RESPONDER"))
                            .contentType("application/json")
                            .content("""
                                    {"totpCode":"123456"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.backupCodes").isArray());
        }

        @Test
        @DisplayName("401 when TOTP code invalid")
        void invalidCode_returns401() throws Exception {
            willThrow(new BusinessException(
                    ErrorCodes.UNAUTHORIZED, "Invalid TOTP code", HttpStatus.UNAUTHORIZED))
                    .given(mfaService).enableMfa(anyString(), any());

            mockMvc.perform(post("/api/v1/auth/mfa/enable")
                            .with(principal("ROLE_RESPONDER"))
                            .contentType("application/json")
                            .content("""
                                    {"totpCode":"000000"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── mfa/disable (Bearer JWT — previously completely untested) ───────────

    @Nested
    @DisplayName("POST /mfa/disable")
    class MfaDisable {

        @Test
        @DisplayName("401 unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/auth/mfa/disable")
                            .contentType("application/json")
                            .content("""
                                    {"password":"secret123","totpCode":"123456"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("204 authenticated — passes the correct principal, password, and totpCode to MfaService")
        void authenticated_returns204() throws Exception {
            mockMvc.perform(post("/api/v1/auth/mfa/disable")
                            .with(principal("ROLE_RESPONDER"))
                            .contentType("application/json")
                            .content("""
                                    {"password":"secret123","totpCode":"123456"}
                                    """))
                    .andExpect(status().isNoContent());

            then(mfaService).should().disableMfa(
                    eq("secret123"), eq("123456"), eq(buildPrincipal("ROLE_RESPONDER")));
        }

        @Test
        @DisplayName("401 when password or TOTP code invalid")
        void invalidCredentials_returns401() throws Exception {
            willThrow(new BusinessException(
                    ErrorCodes.UNAUTHORIZED, "Invalid password or TOTP code",
                    HttpStatus.UNAUTHORIZED))
                    .given(mfaService).disableMfa(anyString(), anyString(), any());

            mockMvc.perform(post("/api/v1/auth/mfa/disable")
                            .with(principal("ROLE_RESPONDER"))
                            .contentType("application/json")
                            .content("""
                                    {"password":"wrong","totpCode":"000000"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── mfa/backup-codes (Bearer JWT — previously completely untested) ──────

    @Nested
    @DisplayName("GET /mfa/backup-codes")
    class GetBackupCodesStatus {

        @Test
        @DisplayName("401 unauthenticated")
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/auth/mfa/backup-codes"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("200 authenticated — passes the correct principal to MfaService")
        void authenticated_returns200() throws Exception {
            given(mfaService.getBackupCodesStatus(eq(buildPrincipal("ROLE_RESPONDER"))))
                    .willReturn(new MfaBackupCodesStatusResponse(5, Instant.now()));

            mockMvc.perform(get("/api/v1/auth/mfa/backup-codes")
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.remainingCodes").value(5));
        }

        @Test
        @DisplayName("409 when MFA not enabled")
        void mfaNotEnabled_returns409() throws Exception {
            willThrow(new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION, "MFA not enabled", HttpStatus.CONFLICT))
                    .given(mfaService).getBackupCodesStatus(any());

            mockMvc.perform(get("/api/v1/auth/mfa/backup-codes")
                            .with(principal("ROLE_RESPONDER")))
                    .andExpect(status().isConflict());
        }
    }

}