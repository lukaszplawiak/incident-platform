package com.incidentplatform.auth.scheduler;

import com.incidentplatform.auth.config.InviteEmailProperties;
import com.incidentplatform.auth.domain.*;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.exception.InviteEmailException;
import com.incidentplatform.auth.service.AuthEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthEmailScheduler")
class AuthEmailSchedulerTest {

    @Mock private AuthEmailOutboxRepository outboxRepository;
    @Mock private AuthEmailService emailService;

    private AuthEmailScheduler scheduler;

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int PENDING_THRESHOLD_SECONDS = 30;

    @BeforeEach
    void setUp() {
        final InviteEmailProperties properties = new InviteEmailProperties(
                "noreply@test.com",
                "http://localhost:3000",
                MAX_RETRY_ATTEMPTS,
                java.time.Duration.ofSeconds(PENDING_THRESHOLD_SECONDS),
                30_000L,
                300_000L);
        scheduler = new AuthEmailScheduler(
                outboxRepository, emailService, properties);
    }

    // ── processPending ────────────────────────────────────────────────────

    @Nested
    @DisplayName("processPending")
    class ProcessPending {

        @Test
        @DisplayName("should do nothing when no PENDING entries exist")
        void shouldDoNothingWhenNoPendingEntries() {
            given(outboxRepository.findPendingOlderThan(any(), any()))
                    .willReturn(List.of());

            scheduler.processPending();

            then(emailService).shouldHaveNoInteractions();
            then(outboxRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("should send email and mark SENT on success")
        void shouldSendEmailAndMarkSentOnSuccess() {
            final AuthEmailOutbox entry = buildPendingEntry();
            given(outboxRepository.findPendingOlderThan(any(), any()))
                    .willReturn(List.of(entry));
            given(outboxRepository.save(any())).willAnswer(i -> i.getArgument(0));

            scheduler.processPending();

            then(emailService).should().sendInviteEmail(
                    entry.getEmail(), "raw-token-abc");
            assertThat(entry.getStatus()).isEqualTo(AuthEmailStatus.SENT);
        }

        @Test
        @DisplayName("should NULL raw_token after successful send")
        void shouldNullRawTokenAfterSend() {
            final AuthEmailOutbox entry = buildPendingEntry();
            given(outboxRepository.findPendingOlderThan(any(), any()))
                    .willReturn(List.of(entry));
            given(outboxRepository.save(any())).willAnswer(i -> i.getArgument(0));

            scheduler.processPending();

            // Security property: raw token must be erased after dispatch
            assertThat(entry.getRawToken()).isNull();
            assertThat(entry.getSentAt()).isNotNull();
        }

        @Test
        @DisplayName("should mark FAILED when SMTP throws on first attempt")
        void shouldMarkFailedOnFirstAttemptFailure() {
            final AuthEmailOutbox entry = buildPendingEntry();
            given(outboxRepository.findPendingOlderThan(any(), any()))
                    .willReturn(List.of(entry));
            given(outboxRepository.save(any())).willAnswer(i -> i.getArgument(0));

            willThrow(new InviteEmailException("user@test.com",
                    "SMTP timeout", new RuntimeException()))
                    .given(emailService).sendInviteEmail(anyString(), anyString());

            scheduler.processPending();

            assertThat(entry.getStatus()).isEqualTo(AuthEmailStatus.FAILED);
            assertThat(entry.getRetryCount()).isEqualTo(1);
            // raw_token retained for retry
            assertThat(entry.getRawToken()).isEqualTo("raw-token-abc");
        }

        @Test
        @DisplayName("should mark PERMANENTLY_FAILED when null rawToken")
        void shouldMarkPermanentlyFailedWhenNullRawToken() {
            final AuthEmailOutbox entry = buildEntryWithNullToken();
            given(outboxRepository.findPendingOlderThan(any(), any()))
                    .willReturn(List.of(entry));
            given(outboxRepository.save(any())).willAnswer(i -> i.getArgument(0));

            scheduler.processPending();

            then(emailService).shouldHaveNoInteractions();
            assertThat(entry.getStatus())
                    .isEqualTo(AuthEmailStatus.PERMANENTLY_FAILED);
        }

        @Test
        @DisplayName("should continue processing other entries after one fails")
        void shouldContinueAfterOneFailure() {
            final AuthEmailOutbox e1 = buildPendingEntry("user1@test.com");
            final AuthEmailOutbox e2 = buildPendingEntry("user2@test.com");
            given(outboxRepository.findPendingOlderThan(any(), any()))
                    .willReturn(List.of(e1, e2));
            given(outboxRepository.save(any())).willAnswer(i -> i.getArgument(0));

            willThrow(new InviteEmailException("user1@test.com",
                    "SMTP timeout", new RuntimeException()))
                    .given(emailService)
                    .sendInviteEmail("user1@test.com", "raw-token-abc");

            scheduler.processPending();

            then(emailService).should(times(2))
                    .sendInviteEmail(anyString(), anyString());
            assertThat(e1.getStatus()).isEqualTo(AuthEmailStatus.FAILED);
            assertThat(e2.getStatus()).isEqualTo(AuthEmailStatus.SENT);
        }

        @Test
        @DisplayName("should save outbox entry after processing")
        void shouldSaveAfterProcessing() {
            final AuthEmailOutbox entry = buildPendingEntry();
            given(outboxRepository.findPendingOlderThan(any(), any()))
                    .willReturn(List.of(entry));
            given(outboxRepository.save(any())).willAnswer(i -> i.getArgument(0));

            scheduler.processPending();

            then(outboxRepository).should().save(entry);
        }
    }

    // ── retryFailed ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("retryFailed")
    class RetryFailed {

        @Test
        @DisplayName("should do nothing when no FAILED entries with retries remaining")
        void shouldDoNothingWhenNoFailedEntries() {
            given(outboxRepository.findFailedWithRemainingRetries(anyInt(), any()))
                    .willReturn(List.of());

            scheduler.retryFailed();

            then(emailService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("should retry and mark SENT on success")
        void shouldRetryAndMarkSentOnSuccess() {
            final AuthEmailOutbox entry = buildFailedEntry(1);
            given(outboxRepository.findFailedWithRemainingRetries(anyInt(), any()))
                    .willReturn(List.of(entry));
            given(outboxRepository.save(any())).willAnswer(i -> i.getArgument(0));

            scheduler.retryFailed();

            then(emailService).should()
                    .sendInviteEmail(entry.getEmail(), "raw-token-abc");
            assertThat(entry.getStatus()).isEqualTo(AuthEmailStatus.SENT);
            assertThat(entry.getRawToken()).isNull();
        }

        @Test
        @DisplayName("should mark PERMANENTLY_FAILED when retries exhausted")
        void shouldMarkPermanentlyFailedWhenRetriesExhausted() {
            // retryCount is already at MAX-1, so this attempt pushes it to MAX
            final AuthEmailOutbox entry = buildFailedEntry(MAX_RETRY_ATTEMPTS - 1);
            given(outboxRepository.findFailedWithRemainingRetries(anyInt(), any()))
                    .willReturn(List.of(entry));
            given(outboxRepository.save(any())).willAnswer(i -> i.getArgument(0));

            willThrow(new InviteEmailException("user@test.com",
                    "SMTP down", new RuntimeException()))
                    .given(emailService).sendInviteEmail(anyString(), anyString());

            scheduler.retryFailed();

            assertThat(entry.getStatus())
                    .isEqualTo(AuthEmailStatus.PERMANENTLY_FAILED);
            // raw_token NULLed on permanent failure
            assertThat(entry.getRawToken()).isNull();
        }

        @Test
        @DisplayName("should increment retry count and stay FAILED when retries remain")
        void shouldIncrementRetryCountOnFailure() {
            final AuthEmailOutbox entry = buildFailedEntry(1);
            given(outboxRepository.findFailedWithRemainingRetries(anyInt(), any()))
                    .willReturn(List.of(entry));
            given(outboxRepository.save(any())).willAnswer(i -> i.getArgument(0));

            willThrow(new InviteEmailException("user@test.com",
                    "SMTP timeout", new RuntimeException()))
                    .given(emailService).sendInviteEmail(anyString(), anyString());

            scheduler.retryFailed();

            assertThat(entry.getStatus()).isEqualTo(AuthEmailStatus.FAILED);
            assertThat(entry.getRetryCount()).isEqualTo(2); // was 1, now 2
            // raw_token retained for further retries
            assertThat(entry.getRawToken()).isEqualTo("raw-token-abc");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private AuthEmailOutbox buildPendingEntry() {
        return buildPendingEntry("user@test.com");
    }

    private AuthEmailOutbox buildPendingEntry(String email) {
        final User user = User.forTesting(
                UUID.randomUUID(), "test-tenant", email,
                null, true, List.of());
        final AuthToken token = AuthToken.create(
                user, "test-tenant", "hash",
                AuthToken.Type.INVITE,
                Instant.now().plusSeconds(3600 * 168));
        return AuthEmailOutbox.invitePending(user, token, "raw-token-abc");
    }

    private AuthEmailOutbox buildEntryWithNullToken() {
        final User user = User.forTesting(
                UUID.randomUUID(), "test-tenant", "user@test.com",
                null, true, List.of());
        final AuthToken token = AuthToken.create(
                user, "test-tenant", "hash",
                AuthToken.Type.INVITE,
                Instant.now().plusSeconds(3600));
        // Simulate corrupted entry — markSent was called but status wasn't updated
        final AuthEmailOutbox entry = AuthEmailOutbox.invitePending(user, token, null);
        return entry;
    }

    private AuthEmailOutbox buildFailedEntry(int retryCount) {
        final AuthEmailOutbox entry = buildPendingEntry();
        for (int i = 0; i < retryCount; i++) {
            entry.markFailed("SMTP timeout");
        }
        return entry;
    }
}