package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.AcceptInviteRequest;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("InviteService")
class InviteServiceTest {

    @Mock
    private AuthTokenService authTokenService;

    @Mock
    private UserRepository userRepository;

    private InviteService service;

    private static final String TENANT_ID = "test-tenant";
    private static final String RAW_TOKEN = "raw-invite-token";
    private static final String NEW_PASSWORD = "SuperSecret123!";

    private final User user = User.forTesting(
            UUID.randomUUID(), TENANT_ID, "user@example.com",
            null, true, List.of("ROLE_RESPONDER"));

    @BeforeEach
    void setUp() {
        service = new InviteService(authTokenService, userRepository);
    }

    // ── acceptInvite — success ────────────────────────────────────────────

    @Nested
    @DisplayName("acceptInvite — success")
    class AcceptInviteSuccess {

        @Test
        @DisplayName("sets BCrypt-hashed password on user")
        void setsHashedPassword() {
            // given
            final AuthToken token = buildValidToken();
            given(authTokenService.consumeToken(
                    eq(RAW_TOKEN), eq(AuthToken.Type.INVITE)))
                    .willReturn(token);
            given(userRepository.save(any(User.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // when
            service.acceptInvite(new AcceptInviteRequest(RAW_TOKEN, NEW_PASSWORD));

            // then — password hash is set, never stores plain text
            final ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            then(userRepository).should().save(captor.capture());
            final String hash = captor.getValue().getPasswordHash();
            assertThat(hash).isNotNull();
            assertThat(hash).isNotEqualTo(NEW_PASSWORD);
            assertThat(hash).startsWith("$2a$");  // BCrypt prefix
        }

        @Test
        @DisplayName("consumes token with INVITE type")
        void consumesInviteToken() {
            // given
            final AuthToken token = buildValidToken();
            given(authTokenService.consumeToken(any(), any()))
                    .willReturn(token);
            given(userRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // when
            service.acceptInvite(new AcceptInviteRequest(RAW_TOKEN, NEW_PASSWORD));

            // then
            then(authTokenService).should()
                    .consumeToken(RAW_TOKEN, AuthToken.Type.INVITE);
        }

        @Test
        @DisplayName("saves user after setting password")
        void savesUserAfterSettingPassword() {
            // given
            given(authTokenService.consumeToken(any(), any()))
                    .willReturn(buildValidToken());
            given(userRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // when
            service.acceptInvite(new AcceptInviteRequest(RAW_TOKEN, NEW_PASSWORD));

            // then
            then(userRepository).should().save(any(User.class));
        }
    }

    // ── acceptInvite — invalid token ──────────────────────────────────────

    @Nested
    @DisplayName("acceptInvite — invalid token")
    class InvalidToken {

        @Test
        @DisplayName("propagates 401 from AuthTokenService on invalid token")
        void propagates401OnInvalidToken() {
            // given
            given(authTokenService.consumeToken(any(), any()))
                    .willThrow(new BusinessException(
                            "UNAUTHORIZED",
                            "Token is invalid, expired, or already used",
                            HttpStatus.UNAUTHORIZED));

            // when / then
            assertThatThrownBy(() ->
                    service.acceptInvite(
                            new AcceptInviteRequest(RAW_TOKEN, NEW_PASSWORD)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("invalid, expired, or already used")
                    .extracting(ex -> ((BusinessException) ex).getHttpStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("does not save user when token is invalid")
        void doesNotSaveUserOnInvalidToken() {
            // given
            given(authTokenService.consumeToken(any(), any()))
                    .willThrow(new BusinessException(
                            "UNAUTHORIZED", "invalid", HttpStatus.UNAUTHORIZED));

            // when
            assertThatThrownBy(() ->
                    service.acceptInvite(
                            new AcceptInviteRequest(RAW_TOKEN, NEW_PASSWORD)))
                    .isInstanceOf(BusinessException.class);

            // then
            then(userRepository).shouldHaveNoInteractions();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private AuthToken buildValidToken() {
        return AuthToken.forTesting(
                user, TENANT_ID, "hash-of-token",
                AuthToken.Type.INVITE,
                Instant.now().plusSeconds(3600),
                null);
    }
}