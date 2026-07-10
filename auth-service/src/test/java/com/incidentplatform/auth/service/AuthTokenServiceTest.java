package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthTokenRepository;
import com.incidentplatform.shared.security.JwtUtils;
import com.incidentplatform.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthTokenService")
class AuthTokenServiceTest {

    @Mock
    private AuthTokenRepository tokenRepository;

    @Mock
    private JwtUtils jwtUtils;

    private AuthTokenService service;

    private static final String TENANT_ID = "test-tenant";
    private final User user = User.forTesting(
            UUID.randomUUID(), TENANT_ID, "user@example.com",
            null, true, List.of());

    @BeforeEach
    void setUp() {
        // JwtUtils is needed for generateRefreshToken() and rotateRefreshToken().
        // TTL is stubbed where needed in individual tests.
        service = new AuthTokenService(tokenRepository, jwtUtils);
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

            service.consumeToken("raw-token", AuthToken.Type.INVITE);

            assertThat(stored.isUsed()).isTrue();
            assertThat(stored.getUsedAt()).isNotNull();
        }

        @Test
        @DisplayName("saves updated token after marking used")
        void savesAfterMarkingUsed() {
            final AuthToken stored = AuthToken.forTesting(
                    user, TENANT_ID, "hash-value",
                    AuthToken.Type.INVITE,
                    Instant.now().plusSeconds(3600), null);

            given(tokenRepository.findValidByHashAndType(any(), any(), any()))
                    .willReturn(Optional.of(stored));

            service.consumeToken("raw-token", AuthToken.Type.INVITE);

            then(tokenRepository).should().save(stored);
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