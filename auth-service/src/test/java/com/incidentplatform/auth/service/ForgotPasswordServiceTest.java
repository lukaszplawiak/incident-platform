package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.AuthEmailStatus;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.auth.service.AuthTokenService.InviteTokenResult;
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
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("ForgotPasswordService")
class ForgotPasswordServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AuthTokenService authTokenService;
    @Mock private AuthEmailOutboxRepository outboxRepository;

    private ForgotPasswordService service;

    private static final String TENANT_ID = "test-tenant";
    private static final String EMAIL = "user@firma.pl";
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ForgotPasswordService(
                userRepository, authTokenService, outboxRepository);
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── user enumeration protection ───────────────────────────────────────

    @Nested
    @DisplayName("user enumeration protection")
    class UserEnumerationProtection {

        @Test
        @DisplayName("does nothing when email not found — no exception, no token")
        void doesNothingWhenEmailNotFound() {
            given(userRepository.findByEmailAndTenantId(
                    EMAIL, TENANT_ID)).willReturn(Optional.empty());

            // Must NOT throw — caller always returns 202
            service.initiateReset(EMAIL, TENANT_ID);

            then(authTokenService).shouldHaveNoInteractions();
            then(outboxRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("does nothing silently for soft-deleted user")
        void doesNothingForSoftDeletedUser() {
            // @SQLRestriction("deleted_at IS NULL") on User excludes soft-deleted automatically
            given(userRepository.findByEmailAndTenantId(
                    EMAIL, TENANT_ID)).willReturn(Optional.empty());

            service.initiateReset(EMAIL, TENANT_ID);

            then(authTokenService).shouldHaveNoInteractions();
        }
    }

    // ── successful reset initiation ───────────────────────────────────────

    @Nested
    @DisplayName("initiateReset — success")
    class InitiateResetSuccess {

        @Test
        @DisplayName("generates PASSWORD_RESET token and writes PENDING outbox entry")
        void generatesTokenAndWritesOutboxEntry() {
            final User user = buildUser();
            given(userRepository.findByEmailAndTenantId(
                    EMAIL, TENANT_ID)).willReturn(Optional.of(user));
            given(outboxRepository.findLatestByUserIdAndType(
                    USER_ID, AuthEmailType.PASSWORD_RESET))
                    .willReturn(Optional.empty());
            given(authTokenService.generatePasswordResetTokenWithEntity(
                    any(), anyString()))
                    .willReturn(buildTokenResult(user));
            given(outboxRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.initiateReset(EMAIL, TENANT_ID);

            then(authTokenService).should()
                    .generatePasswordResetTokenWithEntity(eq(user), eq(TENANT_ID));

            final ArgumentCaptor<AuthEmailOutbox> captor =
                    ArgumentCaptor.forClass(AuthEmailOutbox.class);
            then(outboxRepository).should().save(captor.capture());

            final AuthEmailOutbox saved = captor.getValue();
            assertThat(saved.getEmailType()).isEqualTo(AuthEmailType.PASSWORD_RESET);
            assertThat(saved.getStatus()).isEqualTo(AuthEmailStatus.PENDING);
            assertThat(saved.getRawToken()).isEqualTo("raw-reset-token");
            assertThat(saved.getEmail()).isEqualTo(EMAIL);
        }

        @Test
        @DisplayName("does not write outbox when reset already PENDING — idempotency guard")
        void doesNotWriteWhenAlreadyPending() {
            final User user = buildUser();
            given(userRepository.findByEmailAndTenantId(
                    EMAIL, TENANT_ID)).willReturn(Optional.of(user));

            // Simulate existing PENDING entry
            final AuthEmailOutbox existing = AuthEmailOutbox.passwordResetPending(
                    user,
                    AuthToken.create(user, TENANT_ID, "hash",
                            AuthToken.Type.PASSWORD_RESET,
                            Instant.now().plusSeconds(900)),
                    "existing-raw-token");
            given(outboxRepository.findLatestByUserIdAndType(
                    USER_ID, AuthEmailType.PASSWORD_RESET))
                    .willReturn(Optional.of(existing));

            service.initiateReset(EMAIL, TENANT_ID);

            then(authTokenService).shouldHaveNoInteractions();
            then(outboxRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("writes new outbox entry when previous was PERMANENTLY_FAILED")
        void writesNewEntryWhenPreviousPermanentlyFailed() {
            final User user = buildUser();
            given(userRepository.findByEmailAndTenantId(
                    EMAIL, TENANT_ID)).willReturn(Optional.of(user));

            final AuthEmailOutbox failed = AuthEmailOutbox.passwordResetPending(
                    user,
                    AuthToken.create(user, TENANT_ID, "hash",
                            AuthToken.Type.PASSWORD_RESET,
                            Instant.now().plusSeconds(900)),
                    "old-raw-token");
            failed.markPermanentlyFailed("SMTP down");
            given(outboxRepository.findLatestByUserIdAndType(
                    USER_ID, AuthEmailType.PASSWORD_RESET))
                    .willReturn(Optional.of(failed));
            given(authTokenService.generatePasswordResetTokenWithEntity(
                    any(), anyString()))
                    .willReturn(buildTokenResult(user));
            given(outboxRepository.save(any())).willAnswer(i -> i.getArgument(0));

            service.initiateReset(EMAIL, TENANT_ID);

            then(authTokenService).should()
                    .generatePasswordResetTokenWithEntity(any(), anyString());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private User buildUser() {
        return User.forTesting(USER_ID, TENANT_ID, EMAIL,
                "bcrypt-hash", true, List.of("ROLE_RESPONDER"));
    }

    private InviteTokenResult buildTokenResult(User user) {
        final AuthToken token = AuthToken.create(
                user, TENANT_ID, "hash",
                AuthToken.Type.PASSWORD_RESET,
                Instant.now().plusSeconds(900));
        return new InviteTokenResult("raw-reset-token", token);
    }
}