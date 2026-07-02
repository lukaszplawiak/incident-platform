package com.incidentplatform.auth.api;

import com.incidentplatform.auth.config.SecurityConfig;
import com.incidentplatform.auth.dto.LoginResponse;
import com.incidentplatform.auth.service.AuthService;
import com.incidentplatform.auth.service.InviteService;
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
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security and contract tests for {@link AuthController}.
 *
 * <p>Verifies that {@code /api/v1/auth/login} is reachable without
 * authentication (it is the endpoint that issues the first token — see
 * {@link SecurityConfig} Javadoc) and that request validation / service
 * errors map to the correct HTTP status codes.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, UnauthorizedEntryPoint.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-minimum-64-characters-long-for-hs256-algorithm-padding",
        "jwt.expiration-ms=86400000",
        "jwt.service-expiration=PT1H",
        "spring.application.name=auth-service"
})
@DisplayName("AuthController")
class AuthControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private InviteService inviteService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private ServiceTokenProvider serviceTokenProvider;

    // ── public access ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("public access — no token required")
    class PublicAccess {

        @Test
        @DisplayName("POST /login — 200 without Authorization header")
        void login_noTokenRequired_returns200() throws Exception {
            given(authService.login(any())).willReturn(new LoginResponse(
                    "jwt-token", UUID.randomUUID(), "test-tenant",
                    "user@example.com", List.of("ROLE_ADMIN"),
                    Instant.now().plusSeconds(3600)));

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType("application/json")
                            .content("""
                                    {"email":"user@example.com","password":"secret123"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").value("jwt-token"));
        }
    }

    // ── validation ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("request validation")
    class Validation {

        @Test
        @DisplayName("missing email — 400")
        void missingEmail_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType("application/json")
                            .content("""
                                    {"password":"secret123"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("malformed email — 400")
        void malformedEmail_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType("application/json")
                            .content("""
                                    {"email":"not-an-email","password":"secret123"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("missing password — 400")
        void missingPassword_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType("application/json")
                            .content("""
                                    {"email":"user@example.com"}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── service errors ───────────────────────────────────────────────────

    @Nested
    @DisplayName("service errors")
    class ServiceErrors {

        @Test
        @DisplayName("AuthService throws 401 — propagated as 401")
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
    // ── accept-invite ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("accept-invite — public access")
    class AcceptInvite {

        @Test
        @DisplayName("POST /accept-invite — 204 without Authorization header")
        void acceptInvite_publicEndpoint_returns204() throws Exception {
            mockMvc.perform(post("/api/v1/auth/accept-invite")
                            .contentType("application/json")
                            .content("""
                                    {"token":"raw-token","password":"SuperSecret123!"}
                                    """))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("POST /accept-invite — 400 when password too short")
        void acceptInvite_shortPassword_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/accept-invite")
                            .contentType("application/json")
                            .content("""
                                    {"token":"raw-token","password":"short"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /accept-invite — 400 when token blank")
        void acceptInvite_blankToken_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/auth/accept-invite")
                            .contentType("application/json")
                            .content("""
                                    {"token":"","password":"SuperSecret123!"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /accept-invite — 401 on invalid token from service")
        void acceptInvite_invalidToken_returns401() throws Exception {
            org.mockito.BDDMockito.willThrow(
                            new com.incidentplatform.shared.exception.BusinessException(
                                    com.incidentplatform.shared.exception.ErrorCodes.UNAUTHORIZED,
                                    "Token is invalid, expired, or already used",
                                    org.springframework.http.HttpStatus.UNAUTHORIZED))
                    .given(inviteService).acceptInvite(any());

            mockMvc.perform(post("/api/v1/auth/accept-invite")
                            .contentType("application/json")
                            .content("""
                                    {"token":"expired-token","password":"SuperSecret123!"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }
    }
}