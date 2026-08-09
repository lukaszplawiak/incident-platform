package com.incidentplatform.auth.scheduler;

import com.incidentplatform.auth.config.InviteEmailProperties;
import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.exception.InviteEmailException;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.service.AuthEmailPersistenceService;
import com.incidentplatform.auth.service.AuthEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * Tests for {@link AuthEmailScheduler}, updated for the fix documented in
 * its own Javadoc: {@code @Transactional} removed from the scheduled
 * methods, writes now go through {@link AuthEmailPersistenceService}
 * instead of directly through the repository. Previously had no dedicated
 * test file at all.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthEmailScheduler")
class AuthEmailSchedulerTest {

    @Mock private AuthEmailOutboxRepository outboxRepository;
    @Mock private AuthEmailService emailService;
    @Mock private AuthEmailPersistenceService persistenceService;

    private AuthEmailScheduler scheduler;

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        final InviteEmailProperties properties = new InviteEmailProperties(
                "noreply@test.com", "http://localhost:4200",
                MAX_RETRY_ATTEMPTS, Duration.ofSeconds(30), 30000L, 300000L);
        scheduler = new AuthEmailScheduler(
                outboxRepository, emailService, persistenceService, properties);
    }

    private AuthEmailOutbox buildInviteEntry() {
        return buildInviteEntry("user@firma.pl");
    }

    private AuthEmailOutbox buildInviteEntry(String email) {
        final User user = User.forTesting(UUID.randomUUID(), TENANT_ID,
                email, "hash", true, List.of("ROLE_RESPONDER"));
        final AuthToken token = AuthToken.create(user, TENANT_ID, "hash",
                AuthToken.Type.INVITE, Instant.now().plusSeconds(3600));
        return AuthEmailOutbox.invitePending(user, token, "raw-token");
    }

    @Nested
    @DisplayName("processPending")
    class ProcessPending {

        @Test
        @DisplayName("does nothing when no PENDING entries exist")
        void doesNothingWhenEmpty() {
            given(outboxRepository.findPendingOlderThan(any(), any()))
                    .willReturn(List.of());

            scheduler.processPending();

            then(emailService).shouldHaveNoInteractions();
            then(persistenceService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("sends the email and calls markSent on success")
        void sendsAndMarksSent() {
            final AuthEmailOutbox entry = buildInviteEntry();
            given(outboxRepository.findPendingOlderThan(any(), any()))
                    .willReturn(List.of(entry));

            scheduler.processPending();

            then(emailService).should().sendInviteEmail("user@firma.pl", "raw-token");
            then(persistenceService).should().markSent(entry.getId());
            then(persistenceService).should(never()).markFailed(any(), anyString());
        }

        /**
         * Preserved from the pre-refactor test file (InviteEmailSchedulerTest,
         * superseded by this file — see git history) — a real, valuable
         * scenario this new file didn't otherwise cover: one entry in a
         * batch failing must not stop the rest of the batch from being
         * processed.
         */
        @Test
        @DisplayName("continues processing remaining entries after one fails")
        void continuesAfterOneFailure() {
            final AuthEmailOutbox failing = buildInviteEntry("failing@firma.pl");
            final AuthEmailOutbox succeeding = buildInviteEntry("succeeding@firma.pl");
            given(outboxRepository.findPendingOlderThan(any(), any()))
                    .willReturn(List.of(failing, succeeding));
            org.mockito.Mockito.doThrow(new InviteEmailException(
                            failing.getEmail(), "SMTP timeout", new RuntimeException()))
                    .when(emailService).sendInviteEmail(failing.getEmail(), "raw-token");

            scheduler.processPending();

            then(emailService).should(org.mockito.Mockito.times(2))
                    .sendInviteEmail(anyString(), anyString());
            then(persistenceService).should().markFailed(failing.getId(), "SMTP timeout");
            then(persistenceService).should().markSent(succeeding.getId());
        }
    }

    @Nested
    @DisplayName("processOne — via processPending")
    class ProcessOneBehaviour {

        @Test
        @DisplayName("calls markPermanentlyFailed without attempting to send " +
                "when rawToken is null")
        void skipsSendWhenRawTokenNull() {
            final AuthEmailOutbox entry = buildInviteEntry();
            // Simulate a null rawToken by marking it sent once (which nulls it)
            // then leaving status as-is for this test's purposes — simplest is
            // to build an entry and reflectively note markSent() nulls rawToken;
            // instead, directly assert on the null-rawToken branch using markSent()
            entry.markSent();

            given(outboxRepository.findPendingOlderThan(any(), any()))
                    .willReturn(List.of(entry));

            scheduler.processPending();

            then(emailService).shouldHaveNoInteractions();
            then(persistenceService).should().markPermanentlyFailed(
                    entry.getId(), "rawToken is null — cannot send email");
        }

        @Test
        @DisplayName("calls markFailed (not permanently) when attempts remain")
        void marksFailedWhenAttemptsRemain() {
            final AuthEmailOutbox entry = buildInviteEntry(); // retryCount == 0
            given(outboxRepository.findPendingOlderThan(any(), any()))
                    .willReturn(List.of(entry));
            org.mockito.Mockito.doThrow(new InviteEmailException(
                            "user@firma.pl", "SMTP down", new RuntimeException()))
                    .when(emailService).sendInviteEmail(anyString(), anyString());

            scheduler.processPending();

            // retryCount 0 -> newRetryCount 1, below MAX_RETRY_ATTEMPTS (3)
            then(persistenceService).should().markFailed(entry.getId(), "SMTP down");
            then(persistenceService).should(never())
                    .markPermanentlyFailed(any(), anyString());
        }

        @Test
        @DisplayName("calls markPermanentlyFailed when retry attempts are exhausted")
        void marksPermanentlyFailedWhenAttemptsExhausted() {
            final AuthEmailOutbox entry = buildInviteEntry();
            // Exhaust retries: entry starts at 0, needs 2 prior failures so
            // this attempt (3rd) reaches MAX_RETRY_ATTEMPTS.
            entry.markFailed("attempt 1");
            entry.markFailed("attempt 2");
            given(outboxRepository.findFailedWithRemainingRetries(anyInt(), any()))
                    .willReturn(List.of(entry));
            org.mockito.Mockito.doThrow(new InviteEmailException(
                            "user@firma.pl", "SMTP still down", new RuntimeException()))
                    .when(emailService).sendInviteEmail(anyString(), anyString());

            scheduler.retryFailed();

            then(persistenceService).should()
                    .markPermanentlyFailed(entry.getId(), "SMTP still down");
            then(persistenceService).should(never())
                    .markFailed(any(), anyString());
        }

        @Test
        @DisplayName("calls markFailed on an unexpected (non-InviteEmailException) error")
        void marksFailedOnUnexpectedException() {
            final AuthEmailOutbox entry = buildInviteEntry();
            given(outboxRepository.findPendingOlderThan(any(), any()))
                    .willReturn(List.of(entry));
            org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                    .when(emailService).sendInviteEmail(anyString(), anyString());

            scheduler.processPending();

            then(persistenceService).should()
                    .markFailed(entry.getId(), "Unexpected error: boom");
        }
    }

    @Nested
    @DisplayName("retryFailed")
    class RetryFailed {

        @Test
        @DisplayName("does nothing when no FAILED entries with remaining retries exist")
        void doesNothingWhenEmpty() {
            given(outboxRepository.findFailedWithRemainingRetries(anyInt(), any()))
                    .willReturn(List.of());

            scheduler.retryFailed();

            then(emailService).shouldHaveNoInteractions();
            then(persistenceService).shouldHaveNoInteractions();
        }
    }
}