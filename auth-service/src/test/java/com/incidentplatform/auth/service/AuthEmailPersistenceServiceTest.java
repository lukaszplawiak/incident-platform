package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailStatus;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.repository.AuthTokenRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.auth.service.AuthEmailPersistenceService.Attempt;
import com.incidentplatform.auth.service.AuthTokenService.GeneratedToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

/**
 * Tests for {@link AuthEmailPersistenceService} (backlog #0-52): the decisions
 * {@code prepareAttempt} makes and the order of its steps, and the mapping of
 * each conditional UPDATE's row count. The SQL itself runs against Postgres in
 * {@code AuthRepositoryIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthEmailPersistenceService")
class AuthEmailPersistenceServiceTest {

    @Mock private AuthEmailOutboxRepository outboxRepository;
    @Mock private AuthTokenRepository tokenRepository;
    @Mock private AuthTokenService tokenService;
    @Mock private UserRepository userRepository;

    private AuthEmailPersistenceService service;

    private static final String TENANT_ID = "test-tenant";
    private static final Duration TOLERANCE = Duration.ofMinutes(3);

    private User user;

    @BeforeEach
    void setUp() {
        service = new AuthEmailPersistenceService(
                outboxRepository, tokenRepository, tokenService, userRepository);
        user = User.forTesting(UUID.randomUUID(), TENANT_ID, "user@firma.pl", null, true,
                List.of("ROLE_RESPONDER"));
    }

    private AuthEmailOutbox request(AuthEmailType type, Duration lifetime) {
        return AuthEmailOutbox.request(user, type, lifetime);
    }

    private void userExists() {
        given(userRepository.findByIdAndTenantId(user.getId(), TENANT_ID)).willReturn(Optional.of(user));
    }

    private void noNewerRequest() {
        given(outboxRepository.existsByUserIdAndEmailTypeAndCreatedAtAfter(any(), any(), any()))
                .willReturn(false);
    }

    @Nested
    @DisplayName("prepareAttempt")
    class PrepareAttempt {

        @Test
        @DisplayName("invalidates earlier tokens of the type, then creates the token to send")
        void createsToken() {
            final AuthEmailOutbox entry = request(AuthEmailType.PASSWORD_RESET, Duration.ofMinutes(15));
            userExists();
            noNewerRequest();
            final AuthToken token = AuthToken.create(user, TENANT_ID, "hash",
                    AuthToken.Type.PASSWORD_RESET, Instant.now().plusSeconds(900));
            given(tokenService.generatePasswordResetTokenWithEntity(user, TENANT_ID))
                    .willReturn(new GeneratedToken("raw", token));
            final Instant now = Instant.now();

            final Attempt attempt = service.prepareAttempt(entry, now, TOLERANCE);

            assertThat(attempt).isEqualTo(new Attempt.Send("raw", token.getId()));
            final InOrder order = inOrder(tokenRepository, tokenService);
            order.verify(tokenRepository).invalidateValidTokens(user.getId(), AuthToken.Type.PASSWORD_RESET, now);
            order.verify(tokenService).generatePasswordResetTokenWithEntity(user, TENANT_ID);
        }

        @Test
        @DisplayName("an invite uses an INVITE token")
        void inviteToken() {
            final AuthEmailOutbox entry = request(AuthEmailType.INVITE, Duration.ofDays(7));
            userExists();
            noNewerRequest();
            given(tokenService.generateInviteTokenWithEntity(user, TENANT_ID)).willReturn(new GeneratedToken(
                    "raw", AuthToken.create(user, TENANT_ID, "h", AuthToken.Type.INVITE, Instant.now())));

            assertThat(service.prepareAttempt(entry, Instant.now(), TOLERANCE)).isInstanceOf(Attempt.Send.class);
            then(tokenRepository).should().invalidateValidTokens(eq(user.getId()), eq(AuthToken.Type.INVITE), any());
        }

        @Test
        @DisplayName("SUPERSEDED when the user no longer exists")
        void supersededWithoutUser() {
            final AuthEmailOutbox entry = request(AuthEmailType.INVITE, Duration.ofDays(7));
            given(userRepository.findByIdAndTenantId(user.getId(), TENANT_ID)).willReturn(Optional.empty());
            given(outboxRepository.close(entry.getId(), AuthEmailStatus.SUPERSEDED, "user no longer exists"))
                    .willReturn(1);

            assertThat(service.prepareAttempt(entry, Instant.now(), TOLERANCE))
                    .isEqualTo(new Attempt.Closed(AuthEmailStatus.SUPERSEDED, "user no longer exists"));
            then(tokenService).shouldHaveNoInteractions();
            then(tokenRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("SUPERSEDED when the invite was already accepted")
        void supersededWhenAccepted() {
            final User accepted = User.forTesting(user.getId(), TENANT_ID, "user@firma.pl", "hash", true,
                    List.of("ROLE_RESPONDER"));
            final AuthEmailOutbox entry = request(AuthEmailType.INVITE, Duration.ofDays(7));
            given(userRepository.findByIdAndTenantId(user.getId(), TENANT_ID)).willReturn(Optional.of(accepted));
            given(outboxRepository.close(entry.getId(), AuthEmailStatus.SUPERSEDED, "invite already accepted"))
                    .willReturn(1);

            assertThat(service.prepareAttempt(entry, Instant.now(), TOLERANCE))
                    .isEqualTo(new Attempt.Closed(AuthEmailStatus.SUPERSEDED, "invite already accepted"));
            then(tokenService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("a password reset for a user with a password is sent (that is its purpose)")
        void resetForUserWithPassword() {
            final User withPassword = User.forTesting(user.getId(), TENANT_ID, "user@firma.pl", "hash", true,
                    List.of("ROLE_RESPONDER"));
            final AuthEmailOutbox entry = request(AuthEmailType.PASSWORD_RESET, Duration.ofMinutes(15));
            given(userRepository.findByIdAndTenantId(user.getId(), TENANT_ID))
                    .willReturn(Optional.of(withPassword));
            noNewerRequest();
            given(tokenService.generatePasswordResetTokenWithEntity(withPassword, TENANT_ID)).willReturn(
                    new GeneratedToken("raw", AuthToken.create(withPassword, TENANT_ID, "h",
                            AuthToken.Type.PASSWORD_RESET, Instant.now())));

            assertThat(service.prepareAttempt(entry, Instant.now(), TOLERANCE)).isInstanceOf(Attempt.Send.class);
        }

        @Test
        @DisplayName("SUPERSEDED when a newer request of the type exists")
        void supersededByNewer() {
            final AuthEmailOutbox entry = request(AuthEmailType.PASSWORD_RESET, Duration.ofMinutes(15));
            userExists();
            given(outboxRepository.existsByUserIdAndEmailTypeAndCreatedAtAfter(
                    user.getId(), AuthEmailType.PASSWORD_RESET, entry.getCreatedAt())).willReturn(true);
            given(outboxRepository.close(entry.getId(), AuthEmailStatus.SUPERSEDED,
                    "replaced by a newer request")).willReturn(1);

            assertThat(service.prepareAttempt(entry, Instant.now(), TOLERANCE)).isEqualTo(
                    new Attempt.Closed(AuthEmailStatus.SUPERSEDED, "replaced by a newer request"));
            then(tokenService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("PERMANENTLY_FAILED once the deadline plus the tolerance has passed")
        void deadlinePassed() {
            final AuthEmailOutbox entry = request(AuthEmailType.PASSWORD_RESET, Duration.ofMinutes(15));
            userExists();
            noNewerRequest();
            given(outboxRepository.close(eq(entry.getId()), eq(AuthEmailStatus.PERMANENTLY_FAILED), anyString()))
                    .willReturn(1);
            final Instant now = entry.getDeadline().plus(TOLERANCE).plusSeconds(1);

            assertThat(service.prepareAttempt(entry, now, TOLERANCE))
                    .isInstanceOfSatisfying(Attempt.Closed.class,
                            closed -> assertThat(closed.status()).isEqualTo(AuthEmailStatus.PERMANENTLY_FAILED));
            then(tokenService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("still sends within the tolerance after the deadline (the last scheduled attempt)")
        void sendsWithinTolerance() {
            final AuthEmailOutbox entry = request(AuthEmailType.PASSWORD_RESET, Duration.ofMinutes(15));
            userExists();
            noNewerRequest();
            given(tokenService.generatePasswordResetTokenWithEntity(user, TENANT_ID)).willReturn(
                    new GeneratedToken("raw", AuthToken.create(user, TENANT_ID, "h",
                            AuthToken.Type.PASSWORD_RESET, Instant.now())));

            assertThat(service.prepareAttempt(entry, entry.getDeadline().plus(TOLERANCE), TOLERANCE))
                    .isInstanceOf(Attempt.Send.class);
        }

        @Test
        @DisplayName("AlreadyClosed when the close finds the entry no longer open")
        void alreadyClosed() {
            final AuthEmailOutbox entry = request(AuthEmailType.INVITE, Duration.ofDays(7));
            given(userRepository.findByIdAndTenantId(user.getId(), TENANT_ID)).willReturn(Optional.empty());
            given(outboxRepository.close(any(), any(), any())).willReturn(0);

            assertThat(service.prepareAttempt(entry, Instant.now(), TOLERANCE))
                    .isInstanceOf(Attempt.AlreadyClosed.class);
        }
    }

    @Nested
    @DisplayName("recording the outcome")
    class Outcome {

        private final UUID entryId = UUID.randomUUID();
        private final UUID tokenId = UUID.randomUUID();

        @Test
        @DisplayName("recordSent reports whether the row changed")
        void recordSent() {
            final Instant now = Instant.now();
            given(outboxRepository.markSent(entryId, now)).willReturn(1, 0);

            assertThat(service.recordSent(entryId, now)).isTrue();
            assertThat(service.recordSent(entryId, now)).isFalse();
            then(tokenRepository).should(never()).markUsedIfUnused(any(), any());
        }

        @Test
        @DisplayName("recordFailed invalidates the undelivered token and reschedules")
        void recordFailed() {
            final Instant now = Instant.now();
            final Instant next = now.plusSeconds(60);
            given(outboxRepository.markFailed(entryId, "SMTP down", next)).willReturn(1);

            assertThat(service.recordFailed(entryId, tokenId, "SMTP down", next, now)).isTrue();
            then(tokenRepository).should().markUsedIfUnused(tokenId, now);
        }

        @Test
        @DisplayName("recordGivenUp invalidates the undelivered token and closes the entry")
        void recordGivenUp() {
            final Instant now = Instant.now();
            given(outboxRepository.markGivenUpAfterAttempt(entryId, "SMTP down")).willReturn(0);

            assertThat(service.recordGivenUp(entryId, tokenId, "SMTP down", now)).isFalse();
            then(tokenRepository).should().markUsedIfUnused(tokenId, now);
        }
    }
}
