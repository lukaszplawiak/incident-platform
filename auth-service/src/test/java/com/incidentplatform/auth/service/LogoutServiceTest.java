package com.incidentplatform.auth.service;

import com.incidentplatform.shared.audit.AuditEventPublisher;
import io.jsonwebtoken.Claims;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("LogoutService")
class LogoutServiceTest {

    @Mock private JwtUtils jwtUtils;
    @Mock private TokenRevocationService revocationService;
    @Mock private AuthTokenService authTokenService;
    @Mock private AuditEventPublisher auditEventPublisher;

    private LogoutService service;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String JTI = "550e8400-e29b-41d4-a716-446655440000";
    private static final String RAW_TOKEN = "raw.jwt.token";

    @Mock
    private Claims claims;

    @BeforeEach
    void setUp() {
        service = new LogoutService(
                jwtUtils, revocationService,
                authTokenService, auditEventPublisher);
    }

    // ── logout — success ──────────────────────────────────────────────────

    @Nested
    @DisplayName("logout — success")
    class LogoutSuccess {

        @Test
        @DisplayName("revokes JWT and invalidates refresh tokens")
        void revokesJwtAndRefreshTokens() {
            final UserPrincipal principal = buildPrincipal();
            given(jwtUtils.validateAndGetClaims(RAW_TOKEN))
                    .willReturn(java.util.Optional.of(claims));
            given(jwtUtils.extractJti(claims))
                    .willReturn(java.util.Optional.of(JTI));
            given(jwtUtils.extractExpiration(claims))
                    .willReturn(java.util.Optional.of(
                            java.util.Date.from(
                                    java.time.Instant.now().plusSeconds(900))));

            service.logout(RAW_TOKEN, principal);

            then(revocationService).should().revoke(eq(JTI), any());
            then(authTokenService).should().invalidateAllRefreshTokens(USER_ID);
        }

        @Test
        @DisplayName("publishes USER_LOGOUT audit event")
        void publishesAuditEvent() {
            final UserPrincipal principal = buildPrincipal();
            given(jwtUtils.validateAndGetClaims(RAW_TOKEN))
                    .willReturn(java.util.Optional.of(claims));
            given(jwtUtils.extractJti(claims))
                    .willReturn(java.util.Optional.of(JTI));
            given(jwtUtils.extractExpiration(claims))
                    .willReturn(java.util.Optional.of(
                            java.util.Date.from(
                                    java.time.Instant.now().plusSeconds(900))));

            service.logout(RAW_TOKEN, principal);

            then(auditEventPublisher).should().publishAuth(
                    eq(USER_ID),
                    eq(TENANT_ID),
                    eq(AuditEventTypes.USER_LOGOUT),
                    eq("auth-service"),
                    eq(USER_ID.toString()),
                    any(),
                    any());
        }
    }

    // ── logout — invalid token ────────────────────────────────────────────

    @Nested
    @DisplayName("logout — invalid token")
    class LogoutInvalidToken {

        @Test
        @DisplayName("throws 401 when token cannot be parsed")
        void throws401OnInvalidToken() {
            final UserPrincipal principal = buildPrincipal();
            given(jwtUtils.validateAndGetClaims(RAW_TOKEN))
                    .willReturn(java.util.Optional.empty());

            assertThatThrownBy(() -> service.logout(RAW_TOKEN, principal))
                    .isInstanceOf(Exception.class);

            then(revocationService).should(never()).revoke(any(), any());
            then(auditEventPublisher).should(never()).publishAuth(
                    any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private UserPrincipal buildPrincipal() {
        return new UserPrincipal(USER_ID, TENANT_ID, "user@example.com",
                List.of("ROLE_RESPONDER"));
    }
}