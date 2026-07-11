package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.domain.AuthEmailStatus;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthTokenRepository;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.auth.service.AuthTokenService.InviteTokenResult;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResendInviteService")
class ResendInviteServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AuthTokenRepository authTokenRepository;
    @Mock private AuthEmailOutboxRepository outboxRepository;
    @Mock private AuthTokenService authTokenService;

    private ResendInviteService service;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String EMAIL = "jan@firma.pl";

    @BeforeEach
    void setUp() {
        service = new ResendInviteService(
                userRepository, authTokenRepository,
                outboxRepository, authTokenService);
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── resendInvite — success paths ─────────────────────────────────────

    @Nested
    @DisplayName("resendInvite — success")
    class ResendInviteSuccess {

        @Test
        @DisplayName("generates new token and queues outbox entry")
        void generatesNewTokenAndQueuesOutboxEntry() {
            givenUserWithoutPassword();
            givenNoExistingOutboxEntry();
            givenNoExistingValidTokens();
            givenTokenGenerationSucceeds();
            given(outboxRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.resendInvite(USER_ID);

            then(authTokenService).should()
                    .generateInviteTokenWithEntity(any(User.class), eq(TENANT_ID));

            final ArgumentCaptor<AuthEmailOutbox> captor =
                    ArgumentCaptor.forClass(AuthEmailOutbox.class);
            then(outboxRepository).should().save(captor.capture());

            final AuthEmailOutbox saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(AuthEmailStatus.PENDING);
            assertThat(saved.getRawToken()).isEqualTo("new-raw-token");
            assertThat(saved.getEmail()).isEqualTo(EMAIL);
        }

        @Test
        @DisplayName("invalidates existing valid tokens before generating new one")
        void invalidatesExistingTokens() {
            final User user = buildUserWithoutPassword();
            given(userRepository.findByIdAndTenantId(
                    USER_ID, TENANT_ID)).willReturn(Optional.of(user));
            givenNoExistingOutboxEntry();
            given(outboxRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // Existing valid token that should be invalidated
            final AuthToken existingToken = AuthToken.create(
                    user, TENANT_ID, "old-hash",
                    AuthToken.Type.INVITE,
                    Instant.now().plusSeconds(3600));
            given(authTokenRepository.findValidByUserIdAndType(
                    eq(USER_ID), eq(AuthToken.Type.INVITE), any()))
                    .willReturn(List.of(existingToken));
            given(authTokenRepository.save(any())).willAnswer(i -> i.getArgument(0));
            givenTokenGenerationSucceeds();

            service.resendInvite(USER_ID);

            // Old token must be marked used
            assertThat(existingToken.isUsed()).isTrue();
            then(authTokenRepository).should().save(existingToken);
        }

        @Test
        @DisplayName("works when no previous valid tokens exist")
        void worksWithNoExistingTokens() {
            givenUserWithoutPassword();
            givenNoExistingOutboxEntry();
            givenNoExistingValidTokens();
            givenTokenGenerationSucceeds();
            given(outboxRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.resendInvite(USER_ID);

            then(authTokenRepository).should(never()).save(any(AuthToken.class));
        }

        @Test
        @DisplayName("works when previous outbox entry is PERMANENTLY_FAILED")
        void worksWhenPreviousEntryIsPermanentlyFailed() {
            final User user = buildUserWithoutPassword();
            given(userRepository.findByIdAndTenantId(
                    USER_ID, TENANT_ID)).willReturn(Optional.of(user));

            final AuthEmailOutbox permanentlyFailed =
                    buildOutboxEntry(user, AuthEmailStatus.PERMANENTLY_FAILED);
            given(outboxRepository.findLatestByUserIdAndType(USER_ID, AuthEmailType.INVITE))
                    .willReturn(Optional.of(permanentlyFailed));
            givenNoExistingValidTokens();
            givenTokenGenerationSucceeds();
            given(outboxRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.resendInvite(USER_ID);

            then(authTokenService).should()
                    .generateInviteTokenWithEntity(any(), anyString());
        }

        @Test
        @DisplayName("works when previous outbox entry is SENT (re-invite after expiry)")
        void worksWhenPreviousEntryIsSent() {
            final User user = buildUserWithoutPassword();
            given(userRepository.findByIdAndTenantId(
                    USER_ID, TENANT_ID)).willReturn(Optional.of(user));

            final AuthEmailOutbox sent =
                    buildOutboxEntry(user, AuthEmailStatus.SENT);
            given(outboxRepository.findLatestByUserIdAndType(USER_ID, AuthEmailType.INVITE))
                    .willReturn(Optional.of(sent));
            givenNoExistingValidTokens();
            givenTokenGenerationSucceeds();
            given(outboxRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.resendInvite(USER_ID);

            then(authTokenService).should()
                    .generateInviteTokenWithEntity(any(), anyString());
        }
    }

    // ── resendInvite — guard failures ────────────────────────────────────

    @Nested
    @DisplayName("resendInvite — guards")
    class ResendInviteGuards {

        @Test
        @DisplayName("throws 404 when user not found in tenant")
        void throws404WhenUserNotFound() {
            given(userRepository.findByIdAndTenantId(
                    USER_ID, TENANT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.resendInvite(USER_ID))
                    .isInstanceOf(ResourceNotFoundException.class);

            then(authTokenService).shouldHaveNoInteractions();
            then(outboxRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("throws 409 when user already accepted invite")
        void throws409WhenUserAlreadyAccepted() {
            // User has a password — invite already accepted
            final User user = User.forTesting(
                    USER_ID, TENANT_ID, EMAIL,
                    "bcrypt-hash", true, List.of("ROLE_RESPONDER"));
            given(userRepository.findByIdAndTenantId(
                    USER_ID, TENANT_ID)).willReturn(Optional.of(user));

            assertThatThrownBy(() -> service.resendInvite(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already accepted")
                    .extracting(ex -> ((BusinessException) ex).getHttpStatus())
                    .isEqualTo(HttpStatus.CONFLICT);

            then(authTokenService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("throws 409 when invite email already PENDING (dispatch in progress)")
        void throws409WhenEmailAlreadyPending() {
            final User user = buildUserWithoutPassword();
            given(userRepository.findByIdAndTenantId(
                    USER_ID, TENANT_ID)).willReturn(Optional.of(user));

            final AuthEmailOutbox pending =
                    buildOutboxEntry(user, AuthEmailStatus.PENDING);
            given(outboxRepository.findLatestByUserIdAndType(USER_ID, AuthEmailType.INVITE))
                    .willReturn(Optional.of(pending));

            assertThatThrownBy(() -> service.resendInvite(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("already queued")
                    .extracting(ex -> ((BusinessException) ex).getHttpStatus())
                    .isEqualTo(HttpStatus.CONFLICT);

            then(authTokenService).shouldHaveNoInteractions();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private User buildUserWithoutPassword() {
        return User.forTesting(
                USER_ID, TENANT_ID, EMAIL,
                null, true, List.of("ROLE_RESPONDER"));
    }

    private void givenUserWithoutPassword() {
        given(userRepository.findByIdAndTenantId(
                USER_ID, TENANT_ID))
                .willReturn(Optional.of(buildUserWithoutPassword()));
    }

    private void givenNoExistingOutboxEntry() {
        given(outboxRepository.findLatestByUserIdAndType(USER_ID, AuthEmailType.INVITE))
                .willReturn(Optional.empty());
    }

    private void givenNoExistingValidTokens() {
        given(authTokenRepository.findValidByUserIdAndType(
                eq(USER_ID), eq(AuthToken.Type.INVITE), any()))
                .willReturn(List.of());
    }

    private void givenTokenGenerationSucceeds() {
        final User user = buildUserWithoutPassword();
        final AuthToken token = AuthToken.create(
                user, TENANT_ID, "new-hash",
                AuthToken.Type.INVITE,
                Instant.now().plusSeconds(3600 * 168));
        given(authTokenService.generateInviteTokenWithEntity(any(), anyString()))
                .willReturn(new InviteTokenResult("new-raw-token", token));
    }

    private AuthEmailOutbox buildOutboxEntry(User user,
                                             AuthEmailStatus status) {
        final AuthToken token = AuthToken.create(
                user, TENANT_ID, "hash",
                AuthToken.Type.INVITE,
                Instant.now().plusSeconds(3600));
        final AuthEmailOutbox entry = AuthEmailOutbox.invitePending(
                user, token, "raw-token");
        if (status == AuthEmailStatus.PERMANENTLY_FAILED) {
            entry.markFailed("error");
            entry.markFailed("error");
            entry.markFailed("error");
            entry.markPermanentlyFailed("3 retries exhausted");
        } else if (status == AuthEmailStatus.SENT) {
            entry.markSent();
        }
        return entry;
    }
}