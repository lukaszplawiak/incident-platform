package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthTokenRepository;
import com.incidentplatform.auth.repository.TeamMemberRepository;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthTokenService")
class AuthTokenServiceTest {

    @Mock private AuthTokenRepository tokenRepository;
    @Mock private JwtUtils jwtUtils;
    @Mock private TeamMemberRepository teamMemberRepository;
    @Mock private TenantAccessService tenantAccessService;

    private AuthTokenService service;

    private static final String TENANT_ID = "test-tenant";
    private final User user = User.forTesting(
            UUID.randomUUID(), TENANT_ID, "user@example.com",
            null, true, List.of());

    @BeforeEach
    void setUp() {
        service = new AuthTokenService(
                tokenRepository, jwtUtils, teamMemberRepository, tenantAccessService);
    }

    // ── generateInviteToken ───────────────────────────────────────────────

    @Nested
    @DisplayName("generateInviteToken")
    class GenerateInviteToken {

        @Test
        @DisplayName("returns non-blank raw token")
        void returnsNonBlankToken() {
            final String token = service.generateInviteToken(user, TENANT_ID);
            assertThat(token).isNotBlank();
        }

        @Test
        @DisplayName("returns different token each call — SecureRandom")
        void returnsDifferentTokenEachCall() {
            final String t1 = service.generateInviteToken(user, TENANT_ID);
            final String t2 = service.generateInviteToken(user, TENANT_ID);
            assertThat(t1).isNotEqualTo(t2);
        }

        @Test
        @DisplayName("persists AuthToken with INVITE type and correct TTL")
        void persistsTokenWithCorrectType() {
            final ArgumentCaptor<AuthToken> captor =
                    ArgumentCaptor.forClass(AuthToken.class);

            service.generateInviteToken(user, TENANT_ID);

            then(tokenRepository).should().save(captor.capture());
            final AuthToken saved = captor.getValue();

            assertThat(saved.getType()).isEqualTo(AuthToken.Type.INVITE);
            assertThat(saved.getExpiresAt())
                    .isAfter(Instant.now().plusSeconds(
                            AuthTokenService.INVITE_TTL_HOURS * 3600 - 5));
        }

        @Test
        @DisplayName("stored token_hash differs from raw token — SHA-256 hashed")
        void storedHashDiffersFromRawToken() {
            final ArgumentCaptor<AuthToken> captor =
                    ArgumentCaptor.forClass(AuthToken.class);

            final String rawToken = service.generateInviteToken(user, TENANT_ID);

            then(tokenRepository).should().save(captor.capture());
            assertThat(captor.getValue().getTokenHash()).isNotEqualTo(rawToken);
        }
    }

    // ── generatePasswordResetToken ────────────────────────────────────────

    @Nested
    @DisplayName("generatePasswordResetToken")
    class GeneratePasswordResetToken {

        @Test
        @DisplayName("persists AuthToken with PASSWORD_RESET type and 15min TTL")
        void persistsWithPasswordResetType() {
            final ArgumentCaptor<AuthToken> captor =
                    ArgumentCaptor.forClass(AuthToken.class);

            service.generatePasswordResetToken(user, TENANT_ID);

            then(tokenRepository).should().save(captor.capture());
            final AuthToken saved = captor.getValue();

            assertThat(saved.getType()).isEqualTo(AuthToken.Type.PASSWORD_RESET);
            assertThat(saved.getExpiresAt())
                    .isAfter(Instant.now().plusSeconds(
                            AuthTokenService.RESET_TTL_MINUTES * 60 - 5));
            assertThat(saved.getExpiresAt())
                    .isBefore(Instant.now().plusSeconds(
                            AuthTokenService.RESET_TTL_MINUTES * 60 + 5));
        }
    }

    // ── generateMfaRecoveryCancelTokenWithEntity (backlog #0-90) ─────────

    @Nested
    @DisplayName("generateMfaRecoveryCancelTokenWithEntity (backlog #0-90)")
    class GenerateMfaRecoveryCancelToken {

        @Test
        @DisplayName("persists an MFA_RECOVERY_CANCEL token living 14 days, the raw token only in the result")
        void persistsCancelToken() {
            final var generated = service.generateMfaRecoveryCancelTokenWithEntity(user, TENANT_ID);

            final ArgumentCaptor<AuthToken> captor = ArgumentCaptor.forClass(AuthToken.class);
            then(tokenRepository).should().save(captor.capture());
            final AuthToken saved = captor.getValue();
            assertThat(saved.getType()).isEqualTo(AuthToken.Type.MFA_RECOVERY_CANCEL);
            assertThat(saved.getExpiresAt()).isBetween(
                    Instant.now().plus(java.time.Duration.ofDays(14)).minusSeconds(5),
                    Instant.now().plus(java.time.Duration.ofDays(14)).plusSeconds(5));
            assertThat(saved.getTokenHash()).isNotEqualTo(generated.rawToken());
            assertThat(AuthTokenService.emailTokenLifetime(AuthToken.Type.MFA_RECOVERY_CANCEL))
                    .isEqualTo(java.time.Duration.ofDays(14));
        }

        @Test
        @DisplayName("the cancel link outlives the longest waiting period a deployment may set")
        void outlivesLongestWaitingPeriod() {
            assertThat(AuthTokenService.MFA_RECOVERY_CANCEL_TTL)
                    .isGreaterThan(com.incidentplatform.auth.config.MfaRecoveryProperties.MAX_WAITING_PERIOD);
        }
    }

    // ── generateRefreshToken ──────────────────────────────────────────────

    @Nested
    @DisplayName("generateRefreshToken")
    class GenerateRefreshToken {

        @Test
        @DisplayName("persists AuthToken with REFRESH type")
        void persistsWithRefreshType() {
            given(jwtUtils.getRefreshTokenTtl()).willReturn(Duration.ofDays(30));

            final ArgumentCaptor<AuthToken> captor =
                    ArgumentCaptor.forClass(AuthToken.class);

            service.generateRefreshToken(user, TENANT_ID, UUID.randomUUID(), null);

            then(tokenRepository).should().save(captor.capture());
            assertThat(captor.getValue().getType())
                    .isEqualTo(AuthToken.Type.REFRESH);
        }

        @Test
        @DisplayName("returns non-blank raw token")
        void returnsNonBlankToken() {
            given(jwtUtils.getRefreshTokenTtl()).willReturn(Duration.ofDays(30));

            final String token = service.generateRefreshToken(
                    user, TENANT_ID, UUID.randomUUID(), null);
            assertThat(token).isNotBlank();
        }

        @Test
        @DisplayName("persists the given sessionId on the generated token")
        void persistsGivenSessionId() {
            given(jwtUtils.getRefreshTokenTtl()).willReturn(Duration.ofDays(30));
            final UUID sessionId = UUID.randomUUID();

            final ArgumentCaptor<AuthToken> captor =
                    ArgumentCaptor.forClass(AuthToken.class);

            service.generateRefreshToken(user, TENANT_ID, sessionId, null);

            then(tokenRepository).should().save(captor.capture());
            assertThat(captor.getValue().getSessionId()).isEqualTo(sessionId);
        }

        @Test
        @DisplayName("records whether the login completed MFA (backlog #0-83)")
        void recordsMfaVerified() {
            given(jwtUtils.getRefreshTokenTtl()).willReturn(Duration.ofDays(30));
            final ArgumentCaptor<AuthToken> captor = ArgumentCaptor.forClass(AuthToken.class);

            final Instant verifiedAt = Instant.parse("2026-10-01T10:00:00Z");
            service.generateRefreshToken(user, TENANT_ID, UUID.randomUUID(), verifiedAt);
            service.generateRefreshToken(user, TENANT_ID, UUID.randomUUID(), null);

            then(tokenRepository).should(org.mockito.Mockito.times(2)).save(captor.capture());
            assertThat(captor.getAllValues()).extracting(AuthToken::getMfaVerifiedAt)
                    .containsExactly(verifiedAt, null);
        }
    }

    // ── rotateRefreshToken ────────────────────────────────────────────────

    @Nested
    @DisplayName("rotateRefreshToken")
    class RotateRefreshToken {

        @Test
        @DisplayName("returns new access token and refresh token")
        void returnsNewTokenPair() {
            final AuthToken stored = AuthToken.forTesting(
                    user, TENANT_ID, "hash",
                    AuthToken.Type.REFRESH,
                    Instant.now().plusSeconds(86400 * 30), null);

            given(tokenRepository.findValidByHashAndType(
                    any(), eq(AuthToken.Type.REFRESH), any()))
                    .willReturn(Optional.of(stored));
            // Fixed (backlog #53): rotateRefreshToken() calls consumeToken()
            // internally, which now calls markUsedIfUnused() instead of
            // save() — must be stubbed, same reasoning as the ConsumeToken
            // tests below.
            given(tokenRepository.markUsedIfUnused(any(), any()))
                    .willReturn(1);
            given(jwtUtils.generateToken(any(), anyString(),
                    anyString(), any(), any(), any(), any()))
                    .willReturn("new-access-token");
            given(jwtUtils.getAccessTokenTtl()).willReturn(Duration.ofMinutes(15));
            given(jwtUtils.getRefreshTokenTtl()).willReturn(Duration.ofDays(30));
            given(teamMemberRepository.findTeamIdsByUserIdAndTenantId(
                    any(), anyString())).willReturn(List.of());
            given(teamMemberRepository.findManagedTeamIdsByUserIdAndTenantId(
                    any(), anyString())).willReturn(List.of());

            final AuthTokenService.RotationResult result =
                    service.rotateRefreshToken("raw-refresh-token");

            assertThat(result.accessToken()).isEqualTo("new-access-token");
            assertThat(result.rawRefreshToken()).isNotBlank();
            assertThat(result.accessExpiresAt()).isAfter(Instant.now());
            assertThat(result.refreshExpiresAt()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("refused for a deactivated user, before any new token (backlog #0-82)")
        void refusedForDeactivatedUser() {
            final User inactive = User.forTesting(UUID.randomUUID(), TENANT_ID, "gone@acme.test", "hash", false,
                    List.of("ROLE_RESPONDER"));
            final AuthToken stored = AuthToken.forTesting(inactive, TENANT_ID, "hash", AuthToken.Type.REFRESH,
                    Instant.now().plusSeconds(86400), null);
            given(tokenRepository.findValidByHashAndType(any(), eq(AuthToken.Type.REFRESH), any()))
                    .willReturn(Optional.of(stored));
            given(tokenRepository.markUsedIfUnused(any(), any())).willReturn(1);

            assertThatThrownBy(() -> service.rotateRefreshToken("raw-refresh-token"))
                    .isInstanceOf(com.incidentplatform.shared.exception.BusinessException.class);
            org.mockito.Mockito.verifyNoInteractions(jwtUtils);
            then(tokenRepository).should(org.mockito.Mockito.never()).save(any());
        }

        @Test
        @DisplayName("refused for a tenant suspended in full before the token is consumed: tenant locked first, "
                + "as a suspension does (backlog #0-82)")
        void refusedForSuspendedTenant() {
            final AuthToken stored = AuthToken.forTesting(user, TENANT_ID, "hash", AuthToken.Type.REFRESH,
                    Instant.now().plusSeconds(86400), null);
            given(tokenRepository.findValidByHashAndType(any(), eq(AuthToken.Type.REFRESH), any()))
                    .willReturn(Optional.of(stored));
            org.mockito.BDDMockito.willThrow(new com.incidentplatform.shared.exception.BusinessException(
                            com.incidentplatform.shared.exception.ErrorCodes.TENANT_SUSPENDED, "suspended",
                            org.springframework.http.HttpStatus.FORBIDDEN))
                    .given(tenantAccessService).requireCanSignIn(TENANT_ID);

            assertThatThrownBy(() -> service.rotateRefreshToken("raw-refresh-token"))
                    .isInstanceOf(com.incidentplatform.shared.exception.BusinessException.class);
            org.mockito.Mockito.verifyNoInteractions(jwtUtils);
            org.mockito.BDDMockito.then(tokenRepository).should(org.mockito.Mockito.never())
                    .markUsedIfUnused(any(), any());
        }

        @Test
        @DisplayName("marks old refresh token as used")
        void marksOldTokenAsUsed() {
            final AuthToken stored = AuthToken.forTesting(
                    user, TENANT_ID, "hash",
                    AuthToken.Type.REFRESH,
                    Instant.now().plusSeconds(86400 * 30), null);

            given(tokenRepository.findValidByHashAndType(
                    any(), eq(AuthToken.Type.REFRESH), any()))
                    .willReturn(Optional.of(stored));
            given(tokenRepository.markUsedIfUnused(any(), any()))
                    .willReturn(1);
            given(jwtUtils.generateToken(any(), anyString(),
                    anyString(), any(), any(), any(), any()))
                    .willReturn("new-access-token");
            given(jwtUtils.getAccessTokenTtl()).willReturn(Duration.ofMinutes(15));
            given(jwtUtils.getRefreshTokenTtl()).willReturn(Duration.ofDays(30));
            given(teamMemberRepository.findTeamIdsByUserIdAndTenantId(
                    any(), anyString())).willReturn(List.of());
            given(teamMemberRepository.findManagedTeamIdsByUserIdAndTenantId(
                    any(), anyString())).willReturn(List.of());

            service.rotateRefreshToken("raw-refresh-token");

            assertThat(stored.isUsed()).isTrue();
        }

        @Test
        @DisplayName("throws 401 when refresh token invalid or already used")
        void throws401OnInvalidToken() {
            given(tokenRepository.findValidByHashAndType(
                    any(), eq(AuthToken.Type.REFRESH), any()))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.rotateRefreshToken("bad-token"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ── consumeToken ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("consumeToken")
    class ConsumeToken {

        @Test
        @DisplayName("returns AuthToken when valid")
        void returnsTokenWhenValid() {
            final AuthToken stored = AuthToken.forTesting(
                    user, TENANT_ID, "hash-value",
                    AuthToken.Type.INVITE,
                    Instant.now().plusSeconds(3600), null);

            given(tokenRepository.findValidByHashAndType(
                    any(), eq(AuthToken.Type.INVITE), any()))
                    .willReturn(Optional.of(stored));
            // Fixed (backlog #53): consumeToken no longer calls save() —
            // markUsedIfUnused must be stubbed, since Mockito's default
            // for an unstubbed int-returning method is 0, which the new
            // code correctly treats as "lost the race" and throws 401.
            given(tokenRepository.markUsedIfUnused(any(), any()))
                    .willReturn(1);

            final AuthToken result = service.consumeToken(
                    "raw-token", AuthToken.Type.INVITE);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("marks token as used after consumption")
        void marksTokenAsUsed() {
            final AuthToken stored = AuthToken.forTesting(
                    user, TENANT_ID, "hash-value",
                    AuthToken.Type.INVITE,
                    Instant.now().plusSeconds(3600), null);

            given(tokenRepository.findValidByHashAndType(any(), any(), any()))
                    .willReturn(Optional.of(stored));
            given(tokenRepository.markUsedIfUnused(any(), any()))
                    .willReturn(1);

            service.consumeToken("raw-token", AuthToken.Type.INVITE);

            assertThat(stored.isUsed()).isTrue();
            assertThat(stored.getUsedAt()).isNotNull();
        }

        @Test
        @DisplayName("throws 401 when token not found or expired")
        void throws401WhenNotFound() {
            given(tokenRepository.findValidByHashAndType(any(), any(), any()))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.consumeToken("bad-token", AuthToken.Type.INVITE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("invalid, expired, or already used");
        }

        /**
         * The actual regression test for backlog #53. Simulates the exact
         * race the fix closes: the read check passes (token still looks
         * valid), but by the time this call's conditional UPDATE runs,
         * some other concurrent call has already claimed the token first
         * — markUsedIfUnused correctly reports 0 rows affected. Verifies
         * this is treated identically to the ordinary not-found case,
         * not surfaced as a different error or, worse, silently ignored.
         */
        @Test
        @DisplayName("throws 401 when markUsedIfUnused reports 0 rows — " +
                "lost a concurrent race for the same token")
        void throws401WhenLostConcurrentRace() {
            final AuthToken stored = AuthToken.forTesting(
                    user, TENANT_ID, "hash-value",
                    AuthToken.Type.INVITE,
                    Instant.now().plusSeconds(3600), null);

            given(tokenRepository.findValidByHashAndType(any(), any(), any()))
                    .willReturn(Optional.of(stored));
            given(tokenRepository.markUsedIfUnused(any(), any()))
                    .willReturn(0);

            assertThatThrownBy(() ->
                    service.consumeToken("raw-token", AuthToken.Type.INVITE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("invalid, expired, or already used");

            // The in-memory entity must NOT be marked used on the losing
            // side — it never actually claimed the token.
            assertThat(stored.isUsed()).isFalse();
        }
    }

    // ── AuthToken.isValid helpers ─────────────────────────────────────────

    @Nested
    @DisplayName("AuthToken.isValid")
    class AuthTokenIsValid {

        @Test
        @DisplayName("not expired, not used → valid")
        void notExpiredNotUsed_isValid() {
            final AuthToken token = AuthToken.forTesting(
                    user, TENANT_ID, "h",
                    AuthToken.Type.INVITE,
                    Instant.now().plusSeconds(3600), null);

            assertThat(token.isValid()).isTrue();
        }

        @Test
        @DisplayName("expired → not valid")
        void expired_notValid() {
            final AuthToken token = AuthToken.forTesting(
                    user, TENANT_ID, "h",
                    AuthToken.Type.INVITE,
                    Instant.now().minusSeconds(1), null);

            assertThat(token.isValid()).isFalse();
            assertThat(token.isExpired()).isTrue();
        }

        @Test
        @DisplayName("used → not valid")
        void used_notValid() {
            final AuthToken token = AuthToken.forTesting(
                    user, TENANT_ID, "h",
                    AuthToken.Type.INVITE,
                    Instant.now().plusSeconds(3600),
                    Instant.now().minusSeconds(60));

            assertThat(token.isValid()).isFalse();
            assertThat(token.isUsed()).isTrue();
        }
    }
}