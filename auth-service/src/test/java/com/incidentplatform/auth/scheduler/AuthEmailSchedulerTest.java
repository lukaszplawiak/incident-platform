package com.incidentplatform.auth.scheduler;

import com.incidentplatform.auth.config.InviteEmailProperties;
import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailStatus;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.exception.InviteEmailException;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.service.AuthEmailPersistenceService;
import com.incidentplatform.auth.service.AuthEmailPersistenceService.Attempt;
import com.incidentplatform.auth.service.AuthEmailService;
import com.incidentplatform.shared.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Tests for {@link AuthEmailScheduler} (backlog #0-52): the two lanes, what it
 * does with each {@link Attempt} {@code prepareAttempt} returns, the retry
 * schedule up to the deadline, the counters the alerts read, the processing
 * budget and the retention purge. The database side of each step is in
 * {@code AuthEmailPersistenceServiceTest} and {@code AuthRepositoryIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthEmailScheduler")
class AuthEmailSchedulerTest {

    @Mock private AuthEmailOutboxRepository outboxRepository;
    @Mock private AuthEmailService emailService;
    @Mock private AuthEmailPersistenceService persistenceService;

    private SimpleMeterRegistry meterRegistry;
    private AuthEmailScheduler scheduler;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID TOKEN_ID = UUID.randomUUID();
    private static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(6));
    // Large on purpose: tests stubbing several entries exercise "process
    // everything returned"; the cap itself is covered in BatchSizeCap.
    private static final int BATCH_SIZE = 100;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = scheduler(BATCH_SIZE, Duration.ofMinutes(2));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AuthEmailScheduler scheduler(int batchSize, Duration budget) {
        final InviteEmailProperties properties = new InviteEmailProperties(
                "noreply@test.com", "http://localhost:4200", batchSize, 30000L,
                BACKOFF, budget, Duration.ofDays(30));
        return new AuthEmailScheduler(
                outboxRepository, emailService, persistenceService, properties, meterRegistry);
    }

    private static AuthEmailOutbox entry(String email, AuthEmailType type, Duration lifetime) {
        final User user = User.forTesting(UUID.randomUUID(), TENANT_ID,
                email, null, true, List.of("ROLE_RESPONDER"));
        return AuthEmailOutbox.request(user, type, lifetime);
    }

    private static AuthEmailOutbox invite(String email) {
        return entry(email, AuthEmailType.INVITE, Duration.ofDays(7));
    }

    private static AuthEmailOutbox invite() {
        return invite("user@firma.pl");
    }

    private void duePending(AuthEmailOutbox... entries) {
        given(outboxRepository.findDuePending(any(), any())).willReturn(List.of(entries));
    }

    private void dueFailed(AuthEmailOutbox... entries) {
        given(outboxRepository.findDueFailed(any(), any())).willReturn(List.of(entries));
    }

    private void readyToSend(AuthEmailOutbox entry) {
        given(persistenceService.prepareAttempt(eq(entry), any(), any()))
                .willReturn(new Attempt.Send("raw-" + entry.getEmail(), TOKEN_ID));
    }

    private void smtpFails(AuthEmailOutbox entry, String message) {
        willThrow(new InviteEmailException(entry.getEmail(), message, new RuntimeException()))
                .given(emailService).sendInviteEmail(eq(entry.getEmail()), anyString());
    }

    private double count(String name, String... tags) {
        return meterRegistry.get(name).tags(tags).counter().count();
    }

    @Nested
    @DisplayName("sending")
    class Sending {

        @Test
        @DisplayName("does nothing when no entry is due")
        void doesNothingWhenEmpty() {
            duePending();

            scheduler.processPending();

            then(emailService).shouldHaveNoInteractions();
            then(persistenceService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("sends with the token prepareAttempt created, records SENT, counts the send")
        void sendsAndRecordsSent() {
            final AuthEmailOutbox entry = invite();
            duePending(entry);
            readyToSend(entry);
            given(persistenceService.recordSent(eq(entry.getId()), any())).willReturn(true);

            scheduler.processPending();

            then(emailService).should().sendInviteEmail("user@firma.pl", "raw-user@firma.pl");
            then(persistenceService).should().recordSent(eq(entry.getId()), any());
            assertThat(count(AuthEmailScheduler.SEND_COUNTER, "type", "INVITE", "outcome", "sent"))
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("routes a password reset to the reset template")
        void sendsPasswordReset() {
            final AuthEmailOutbox entry = entry("user@firma.pl", AuthEmailType.PASSWORD_RESET,
                    Duration.ofMinutes(15));
            duePending(entry);
            readyToSend(entry);

            scheduler.processPending();

            then(emailService).should().sendPasswordResetEmail("user@firma.pl", "raw-user@firma.pl");
        }

        @Test
        @DisplayName("an entry closed by someone else after the send still counts the email as sent")
        void sentButAlreadyClosed() {
            final AuthEmailOutbox entry = invite();
            duePending(entry);
            readyToSend(entry);
            given(persistenceService.recordSent(eq(entry.getId()), any())).willReturn(false);

            scheduler.processPending();

            assertThat(count(AuthEmailScheduler.SEND_COUNTER, "type", "INVITE", "outcome", "sent"))
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("each lane asks for entries due now")
        void lanesAskForDueNow() {
            duePending();
            dueFailed();
            final Instant before = Instant.now();

            scheduler.processPending();
            scheduler.retryFailed();

            final ArgumentCaptor<Instant> pendingNow = ArgumentCaptor.forClass(Instant.class);
            final ArgumentCaptor<Instant> failedNow = ArgumentCaptor.forClass(Instant.class);
            then(outboxRepository).should().findDuePending(pendingNow.capture(), any());
            then(outboxRepository).should().findDueFailed(failedNow.capture(), any());
            assertThat(pendingNow.getValue()).isCloseTo(before, within(5, ChronoUnit.SECONDS));
            assertThat(failedNow.getValue()).isCloseTo(before, within(5, ChronoUnit.SECONDS));
        }

        /** One entry failing must not stop the rest of the batch. */
        @Test
        @DisplayName("continues with the remaining entries after one send fails")
        void continuesAfterOneFailure() {
            final AuthEmailOutbox failing = invite("failing@firma.pl");
            final AuthEmailOutbox succeeding = invite("succeeding@firma.pl");
            duePending(failing, succeeding);
            readyToSend(failing);
            readyToSend(succeeding);
            smtpFails(failing, "SMTP timeout");

            scheduler.processPending();

            then(emailService).should(times(2)).sendInviteEmail(anyString(), anyString());
            then(persistenceService).should().recordFailed(
                    eq(failing.getId()), eq(TOKEN_ID), eq("SMTP timeout"), any(), any());
            then(persistenceService).should().recordSent(eq(succeeding.getId()), any());
        }

        @Test
        @DisplayName("continues with the batch when a database step throws")
        void continuesWhenDatabaseStepThrows() {
            final AuthEmailOutbox first = invite("first@firma.pl");
            final AuthEmailOutbox second = invite("second@firma.pl");
            duePending(first, second);
            willThrow(new DataAccessResourceFailureException("db down"))
                    .given(persistenceService).prepareAttempt(eq(first), any(), any());
            readyToSend(second);

            scheduler.processPending();

            then(emailService).should().sendInviteEmail(eq("second@firma.pl"), anyString());
            assertThat(TenantContext.getOrNull()).isNull();
        }
    }

    @Nested
    @DisplayName("entries closed by prepareAttempt")
    class Closed {

        @Test
        @DisplayName("SUPERSEDED: nothing sent, nothing counted")
        void superseded() {
            final AuthEmailOutbox entry = invite();
            duePending(entry);
            given(persistenceService.prepareAttempt(eq(entry), any(), any()))
                    .willReturn(new Attempt.Closed(AuthEmailStatus.SUPERSEDED, "replaced by a newer request"));

            scheduler.processPending();

            then(emailService).shouldHaveNoInteractions();
            assertNoGiveUpCounted();
        }

        @Test
        @DisplayName("PERMANENTLY_FAILED (deadline passed): nothing sent, counted as DEADLINE_PASSED")
        void deadlinePassed() {
            final AuthEmailOutbox entry = invite();
            duePending(entry);
            given(persistenceService.prepareAttempt(eq(entry), any(), any()))
                    .willReturn(new Attempt.Closed(AuthEmailStatus.PERMANENTLY_FAILED, "deadline passed"));

            scheduler.processPending();

            then(emailService).shouldHaveNoInteractions();
            assertThat(count(AuthEmailScheduler.PERMANENTLY_FAILED_COUNTER,
                    "type", "INVITE", "reason", "DEADLINE_PASSED")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("already closed by someone else: nothing sent, nothing counted")
        void alreadyClosed() {
            final AuthEmailOutbox entry = invite();
            duePending(entry);
            given(persistenceService.prepareAttempt(eq(entry), any(), any()))
                    .willReturn(new Attempt.AlreadyClosed());

            scheduler.processPending();

            then(emailService).shouldHaveNoInteractions();
            assertNoGiveUpCounted();
        }

        @Test
        @DisplayName("passes a deadline tolerance of one interval plus the processing budget")
        void deadlineTolerance() {
            final AuthEmailOutbox entry = invite();
            duePending(entry);
            given(persistenceService.prepareAttempt(eq(entry), any(), any()))
                    .willReturn(new Attempt.AlreadyClosed());

            scheduler.processPending();

            then(persistenceService).should().prepareAttempt(eq(entry), any(),
                    eq(Duration.ofSeconds(30).plus(Duration.ofMinutes(2))));
        }

        private void assertNoGiveUpCounted() {
            for (final AuthEmailScheduler.GiveUpReason reason : AuthEmailScheduler.GiveUpReason.values()) {
                assertThat(count(AuthEmailScheduler.PERMANENTLY_FAILED_COUNTER,
                        "type", "INVITE", "reason", reason.name())).isZero();
            }
        }
    }

    @Nested
    @DisplayName("retry with backoff until the deadline (backlog #0-52)")
    class Retry {

        @Test
        @DisplayName("first failure: FAILED one backoff step later, token invalidated, failure counted")
        void firstFailure() {
            final AuthEmailOutbox entry = invite();
            duePending(entry);
            readyToSend(entry);
            smtpFails(entry, "SMTP down");
            given(persistenceService.recordFailed(any(), any(), any(), any(), any())).willReturn(true);
            final Instant before = Instant.now();

            scheduler.processPending();

            final ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
            then(persistenceService).should().recordFailed(
                    eq(entry.getId()), eq(TOKEN_ID), eq("SMTP down"), next.capture(), any());
            assertThat(next.getValue()).isCloseTo(before.plus(Duration.ofMinutes(1)),
                    within(5, ChronoUnit.SECONDS));
            assertThat(count(AuthEmailScheduler.SEND_COUNTER, "type", "INVITE", "outcome", "failed"))
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("gives up at the deadline, and counts it as RETRY_WINDOW_EXHAUSTED")
        void givesUpAtDeadline() {
            // A reset whose deadline is its creation time, so already here.
            final AuthEmailOutbox entry = entry("user@firma.pl", AuthEmailType.PASSWORD_RESET,
                    Duration.ZERO);
            duePending(entry);
            readyToSend(entry);
            willThrow(new InviteEmailException("user@firma.pl", "SMTP down", new RuntimeException()))
                    .given(emailService).sendPasswordResetEmail(anyString(), anyString());
            given(persistenceService.recordGivenUp(eq(entry.getId()), eq(TOKEN_ID), eq("SMTP down"), any()))
                    .willReturn(true);

            scheduler.processPending();

            then(persistenceService).should(never()).recordFailed(any(), any(), any(), any(), any());
            assertThat(count(AuthEmailScheduler.PERMANENTLY_FAILED_COUNTER,
                    "type", "PASSWORD_RESET", "reason", "RETRY_WINDOW_EXHAUSTED")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("does not count a give-up the entry was already closed for")
        void doesNotCountGiveUpOfClosedEntry() {
            final AuthEmailOutbox entry = entry("user@firma.pl", AuthEmailType.PASSWORD_RESET,
                    Duration.ZERO);
            duePending(entry);
            readyToSend(entry);
            willThrow(new InviteEmailException("user@firma.pl", "SMTP down", new RuntimeException()))
                    .given(emailService).sendPasswordResetEmail(anyString(), anyString());
            given(persistenceService.recordGivenUp(any(), any(), any(), any())).willReturn(false);

            scheduler.processPending();

            assertThat(count(AuthEmailScheduler.PERMANENTLY_FAILED_COUNTER,
                    "type", "PASSWORD_RESET", "reason", "RETRY_WINDOW_EXHAUSTED")).isZero();
        }

        /** Backlog #81: an unexpected error follows the same bounded path. */
        @Test
        @DisplayName("treats an unexpected exception like an SMTP failure (backlog #81)")
        void unexpectedExceptionIsBounded() {
            final AuthEmailOutbox entry = invite();
            duePending(entry);
            readyToSend(entry);
            willThrow(new RuntimeException("boom"))
                    .given(emailService).sendInviteEmail(anyString(), anyString());

            scheduler.processPending();

            then(persistenceService).should().recordFailed(
                    eq(entry.getId()), eq(TOKEN_ID), eq("boom"), any(), any());
        }

        @Test
        @DisplayName("uses the exception class name when the message is null")
        void usesClassNameWhenNoMessage() {
            final AuthEmailOutbox entry = invite();
            duePending(entry);
            readyToSend(entry);
            willThrow(new IllegalStateException())
                    .given(emailService).sendInviteEmail(anyString(), anyString());

            scheduler.processPending();

            then(persistenceService).should().recordFailed(
                    eq(entry.getId()), eq(TOKEN_ID), eq("IllegalStateException"), any(), any());
        }
    }

    @Nested
    @DisplayName("processing budget (performance review)")
    class Budget {

        @Test
        @DisplayName("stops taking entries once the budget is used up, but always does one")
        void stopsAfterBudget() {
            final AuthEmailScheduler tinyBudget = scheduler(BATCH_SIZE, Duration.ofNanos(1));
            final AuthEmailOutbox first = invite("first@firma.pl");
            final AuthEmailOutbox second = invite("second@firma.pl");
            duePending(first, second);
            readyToSend(first);

            tinyBudget.processPending();

            then(emailService).should().sendInviteEmail(eq("first@firma.pl"), anyString());
            then(persistenceService).should(never()).prepareAttempt(eq(second), any(), any());
        }

        @Test
        @DisplayName("a budget that would outlive the lock fails at startup")
        void rejectsBudgetBeyondLock() {
            assertThatThrownBy(() -> scheduler(BATCH_SIZE, Duration.ofMinutes(4)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("processing-budget");
            assertThat(AuthEmailScheduler.validated(Duration.ofMinutes(3))).isEqualTo(Duration.ofMinutes(3));
            assertThatThrownBy(() -> AuthEmailScheduler.validated(Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("metrics")
    class Metrics {

        @Test
        @DisplayName("registers every counter at 0 so the alerts see the first increase")
        void preRegistersCounters() {
            for (final String type : List.of("INVITE", "PASSWORD_RESET")) {
                assertThat(count(AuthEmailScheduler.SEND_COUNTER, "type", type, "outcome", "sent")).isZero();
                assertThat(count(AuthEmailScheduler.SEND_COUNTER, "type", type, "outcome", "failed")).isZero();
                for (final AuthEmailScheduler.GiveUpReason reason : AuthEmailScheduler.GiveUpReason.values()) {
                    assertThat(count(AuthEmailScheduler.PERMANENTLY_FAILED_COUNTER,
                            "type", type, "reason", reason.name())).isZero();
                }
            }
        }
    }

    /** Regression coverage for backlog #55. */
    @Nested
    @DisplayName("TenantContext handling (backlog #55)")
    class TenantContextHandling {

        @Test
        @DisplayName("sets the entry's own tenant while sending and clears it afterwards")
        void usesEntryTenant() {
            final AuthEmailOutbox entry = invite();
            duePending(entry);
            readyToSend(entry);
            final List<String> observed = new ArrayList<>();
            doAnswer(invocation -> {
                observed.add(TenantContext.getOrNull());
                return null;
            }).when(emailService).sendInviteEmail(anyString(), anyString());

            scheduler.processPending();

            assertThat(observed).containsExactly(TENANT_ID);
            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("clears TenantContext even when the send throws")
        void clearsOnFailure() {
            final AuthEmailOutbox entry = invite();
            dueFailed(entry);
            readyToSend(entry);
            willThrow(new RuntimeException("boom"))
                    .given(emailService).sendInviteEmail(anyString(), anyString());

            scheduler.retryFailed();

            assertThat(TenantContext.getOrNull()).isNull();
        }
    }

    /** Regression coverage for backlog #54: each lane caps its own batch. */
    @Nested
    @DisplayName("batch size cap (backlog #54)")
    class BatchSizeCap {

        @Test
        @DisplayName("PENDING lane requests exactly batchSize rows, page 0")
        void pendingLane() {
            duePending();

            scheduler(7, Duration.ofMinutes(2)).processPending();

            final ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            then(outboxRepository).should().findDuePending(any(), pageable.capture());
            assertThat(pageable.getValue().getPageSize()).isEqualTo(7);
            assertThat(pageable.getValue().getPageNumber()).isZero();
        }

        /** A backlog of retries cannot take the slots of new emails. */
        @Test
        @DisplayName("retry lane has its own batch of batchSize rows")
        void retryLane() {
            dueFailed();

            scheduler(5, Duration.ofMinutes(2)).retryFailed();

            final ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            then(outboxRepository).should().findDueFailed(any(), pageable.capture());
            assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
            then(outboxRepository).should(never()).findDuePending(any(), any());
        }
    }

    @Nested
    @DisplayName("retention purge (backlog #0-52)")
    class Purge {

        @Test
        @DisplayName("deletes terminal entries created before now minus the retention")
        void purgesTerminal() {
            final Instant before = Instant.now();

            scheduler.purgeTerminal();

            final ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
            then(outboxRepository).should().deleteTerminalCreatedBefore(cutoff.capture());
            assertThat(cutoff.getValue()).isCloseTo(before.minus(Duration.ofDays(30)),
                    within(5, ChronoUnit.SECONDS));
        }
    }
}
