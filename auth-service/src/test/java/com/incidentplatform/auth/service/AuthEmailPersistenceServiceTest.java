package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailStatus;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.repository.AuthTokenRepository;
import com.incidentplatform.auth.repository.MfaRecoveryRequestRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.auth.service.AuthEmailPersistenceService.Attempt;
import com.incidentplatform.auth.service.AuthTokenService.GeneratedToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
    @Mock private MfaRecoveryRequestRepository recoveryRequestRepository;
    @Mock private TenantAccessService tenantAccessService;

    private AuthEmailPersistenceService service;

    private static final String TENANT_ID = "test-tenant";
    private static final Duration TOLERANCE = Duration.ofMinutes(3);

    private User user;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        service = new AuthEmailPersistenceService(
                outboxRepository, tokenRepository, tokenService, userRepository, recoveryRequestRepository,
                tenantAccessService, meters);
        org.mockito.Mockito.lenient().when(tenantAccessService.accessOf(any()))
                .thenReturn(com.incidentplatform.shared.security.TenantAccess.FULL);
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
        @DisplayName("an MFA recovery notice of an open request gets a cancel token (backlog #0-90)")
        void mfaRecoveryNoticeToken() {
            final UUID requestId = UUID.randomUUID();
            final AuthEmailOutbox entry = AuthEmailOutbox.requestAboutMfaRecovery(user, requestId, Duration.ofHours(24));
            userExists();
            given(recoveryRequestRepository.isPending(requestId)).willReturn(true);
            final AuthToken token = AuthToken.create(user, TENANT_ID, "h", AuthToken.Type.MFA_RECOVERY_CANCEL,
                    Instant.now().plusSeconds(3600));
            given(tokenService.generateMfaRecoveryCancelTokenWithEntity(user, TENANT_ID))
                    .willReturn(new GeneratedToken("raw", token));
            final Instant now = Instant.now();

            assertThat(service.prepareAttempt(entry, now, TOLERANCE)).isEqualTo(new Attempt.Send("raw", token.getId()));
            then(tokenRepository).should().invalidateValidTokens(user.getId(), AuthToken.Type.MFA_RECOVERY_CANCEL, now);
            then(outboxRepository).should(never()).existsByUserIdAndEmailTypeAndCreatedAtAfter(any(), any(), any());
        }

        @Test
        @DisplayName("an MFA recovery notice of a request that has ended is SUPERSEDED, unsent (backlog #0-90)")
        void mfaRecoveryNoticeOfEndedRequest() {
            final UUID requestId = UUID.randomUUID();
            final AuthEmailOutbox entry = AuthEmailOutbox.requestAboutMfaRecovery(user, requestId, Duration.ofHours(24));
            userExists();
            given(recoveryRequestRepository.isPending(requestId)).willReturn(false);
            given(outboxRepository.close(entry.getId(), AuthEmailStatus.SUPERSEDED,
                    "MFA recovery request no longer pending")).willReturn(1);

            assertThat(service.prepareAttempt(entry, Instant.now(), TOLERANCE)).isEqualTo(new Attempt.Closed(
                    AuthEmailStatus.SUPERSEDED, "MFA recovery request no longer pending"));
            then(tokenService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("a completed MFA recovery carries a password-reset token (backlog #0-90)")
        void mfaRecoveryCompletedToken() {
            final AuthEmailOutbox entry = request(AuthEmailType.MFA_RECOVERY_COMPLETED, Duration.ofHours(24));
            userExists();
            noNewerRequest();
            given(tokenService.generatePasswordResetTokenWithEntity(user, TENANT_ID)).willReturn(new GeneratedToken(
                    "raw", AuthToken.create(user, TENANT_ID, "h", AuthToken.Type.PASSWORD_RESET, Instant.now())));

            assertThat(service.prepareAttempt(entry, Instant.now(), TOLERANCE)).isInstanceOf(Attempt.Send.class);
            then(tokenRepository).should().invalidateValidTokens(eq(user.getId()), eq(AuthToken.Type.PASSWORD_RESET), any());
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "{0} while {1}")
        @org.junit.jupiter.params.provider.CsvSource({
                "INVITE, READ_ONLY", "INVITE, NONE", "PASSWORD_RESET, NONE"})
        @DisplayName("a suspended tenant's invite (either mode) or reset (in full) waits: deferred, no token (backlog #0-82)")
        void pausedBySuspension(AuthEmailType type, com.incidentplatform.shared.security.TenantAccess access) {
            final AuthEmailOutbox entry = request(type, Duration.ofDays(7));
            userExists();
            if (type.supersededByNewer()) {
                noNewerRequest();
            }
            given(tenantAccessService.accessOf(TENANT_ID)).willReturn(access);
            given(outboxRepository.defer(eq(entry.getId()), any())).willReturn(1);
            final Instant now = Instant.now();

            assertThat(service.prepareAttempt(entry, now, TOLERANCE)).isEqualTo(
                    new Attempt.Deferred(now.plus(AuthEmailPersistenceService.SUSPENSION_DEFERRAL)));
            then(tokenService).shouldHaveNoInteractions();
            then(tokenRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("a read-only tenant still gets its reset links; security notices go out in full suspension (backlog #0-82)")
        void notPaused() {
            final AuthEmailOutbox reset = request(AuthEmailType.PASSWORD_RESET, Duration.ofMinutes(15));
            userExists();
            noNewerRequest();
            given(tenantAccessService.accessOf(TENANT_ID)).willReturn(
                    com.incidentplatform.shared.security.TenantAccess.READ_ONLY);
            given(tokenService.generatePasswordResetTokenWithEntity(user, TENANT_ID)).willReturn(new GeneratedToken(
                    "raw", AuthToken.create(user, TENANT_ID, "h", AuthToken.Type.PASSWORD_RESET, Instant.now())));
            assertThat(service.prepareAttempt(reset, Instant.now(), TOLERANCE)).isInstanceOf(Attempt.Send.class);

            final AuthEmailOutbox notice = request(AuthEmailType.MFA_ENABLED, Duration.ofHours(24));
            assertThat(service.prepareAttempt(notice, Instant.now(), TOLERANCE))
                    .isEqualTo(new Attempt.Send(null, null));
            then(outboxRepository).should(never()).defer(any(), any());
        }

        @Test
        @DisplayName("a paused entry closed meanwhile is AlreadyClosed")
        void deferOfClosedEntry() {
            final AuthEmailOutbox entry = request(AuthEmailType.INVITE, Duration.ofDays(7));
            userExists();
            noNewerRequest();
            given(tenantAccessService.accessOf(TENANT_ID)).willReturn(
                    com.incidentplatform.shared.security.TenantAccess.NONE);
            given(outboxRepository.defer(eq(entry.getId()), any())).willReturn(0);
            assertThat(service.prepareAttempt(entry, Instant.now(), TOLERANCE)).isEqualTo(new Attempt.AlreadyClosed());
        }

        @Test
        @DisplayName("an MFA notification is sent without a token, and no token is touched (backlog #0-83)")
        void notificationWithoutToken() {
            final AuthEmailOutbox entry = request(AuthEmailType.MFA_ENABLED, Duration.ofHours(24));
            userExists();
            noNewerRequest();

            assertThat(service.prepareAttempt(entry, Instant.now(), TOLERANCE))
                    .isEqualTo(new Attempt.Send(null, null));
            then(tokenRepository).shouldHaveNoInteractions();
            then(tokenService).shouldHaveNoInteractions();
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
        @DisplayName("an API key notice is never superseded: each is about another key (backlog #0-89, review)")
        void apiKeyNoticeNotSuperseded() {
            final AuthEmailOutbox entry = request(AuthEmailType.API_KEY_CREATED, Duration.ofHours(24));
            userExists();

            assertThat(service.prepareAttempt(entry, Instant.now(), TOLERANCE)).isInstanceOf(Attempt.Send.class);
            then(outboxRepository).should(org.mockito.Mockito.never())
                    .existsByUserIdAndEmailTypeAndCreatedAtAfter(any(), any(), any());
            then(outboxRepository).should(org.mockito.Mockito.never()).close(any(), any(), any());
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
            final AuthEmailOutbox entry = request(AuthEmailType.INVITE, Duration.ofDays(7));
            final Instant now = Instant.now();
            given(outboxRepository.markSent(entry.getId(), now)).willReturn(1, 0);

            assertThat(service.recordSent(entry, now)).isTrue();
            assertThat(service.recordSent(entry, now)).isFalse();
            then(tokenRepository).should(never()).markUsedIfUnused(any(), any());
            then(userRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("a sent MFA_ENABLED notice is recorded on the user, for the current enrolment only (backlog #0-83)")
        void recordSentMfaNotice() {
            final AuthEmailOutbox entry = request(AuthEmailType.MFA_ENABLED, Duration.ofHours(24));
            final Instant now = Instant.now();
            given(outboxRepository.markSent(entry.getId(), now)).willReturn(1, 0);
            given(userRepository.recordMfaEnabledNoticeSent(user.getId(), TENANT_ID, entry.getCreatedAt(), now))
                    .willReturn(1);

            assertThat(service.recordSent(entry, now)).isTrue();
            then(userRepository).should().recordMfaEnabledNoticeSent(user.getId(), TENANT_ID, entry.getCreatedAt(), now);
            assertThat(meters.counter("auth.mfa.notice.unrecorded").count()).isZero();

            assertThat(service.recordSent(entry, now)).as("already closed: nothing recorded").isFalse();
            then(userRepository).should(org.mockito.Mockito.times(1))
                    .recordMfaEnabledNoticeSent(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a sent MFA recovery notice starts its request's waiting period, once (backlog #0-90)")
        void recordSentMfaRecoveryNotice() {
            final UUID requestId = UUID.randomUUID();
            final AuthEmailOutbox entry = AuthEmailOutbox.requestAboutMfaRecovery(user, requestId, Duration.ofHours(24));
            final Instant now = Instant.now();
            given(outboxRepository.markSent(entry.getId(), now)).willReturn(1, 1, 0);
            given(recoveryRequestRepository.recordNoticeSent(requestId, now)).willReturn(1, 0);

            assertThat(service.recordSent(entry, now)).isTrue();
            assertThat(service.recordSent(entry, now)).as("request cancelled meanwhile: still SENT").isTrue();
            assertThat(service.recordSent(entry, now)).as("already closed: nothing recorded").isFalse();
            then(recoveryRequestRepository).should(org.mockito.Mockito.times(2)).recordNoticeSent(requestId, now);
            then(userRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("an MFA_ENABLED notice that marks no current factor is still SENT, and counted (backlog #0-83)")
        void recordSentMfaNoticeNotRecorded() {
            final AuthEmailOutbox entry = request(AuthEmailType.MFA_ENABLED, Duration.ofHours(24));
            final Instant now = Instant.now();
            given(outboxRepository.markSent(entry.getId(), now)).willReturn(1);
            given(userRepository.recordMfaEnabledNoticeSent(user.getId(), TENANT_ID, entry.getCreatedAt(), now))
                    .willReturn(0);

            assertThat(service.recordSent(entry, now)).as("the email went out either way").isTrue();
            assertThat(meters.counter("auth.mfa.notice.unrecorded").count()).isEqualTo(1);
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
        @DisplayName("a failed notification has no token to invalidate (backlog #0-83)")
        void notificationFailure() {
            final Instant now = Instant.now();
            given(outboxRepository.markFailed(entryId, "SMTP down", now.plusSeconds(60))).willReturn(1);
            given(outboxRepository.markGivenUpAfterAttempt(entryId, "SMTP down")).willReturn(1);

            assertThat(service.recordFailed(entryId, null, "SMTP down", now.plusSeconds(60), now)).isTrue();
            assertThat(service.recordGivenUp(entryId, null, "SMTP down", now)).isTrue();
            then(tokenRepository).shouldHaveNoInteractions();
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
