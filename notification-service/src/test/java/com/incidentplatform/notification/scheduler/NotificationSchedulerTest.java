package com.incidentplatform.notification.scheduler;

import com.incidentplatform.notification.client.OncallLookupUnavailableException;
import com.incidentplatform.notification.client.SlackWorkspaceLookupUnavailableException;
import com.incidentplatform.notification.config.NotificationSchedulerProperties;
import com.incidentplatform.notification.domain.UndeliverableReason;
import com.incidentplatform.notification.domain.NotificationQueueEntry;
import com.incidentplatform.notification.repository.NotificationQueueRepository;
import com.incidentplatform.notification.service.NotificationPersistenceService;
import com.incidentplatform.notification.service.NotificationService;
import com.incidentplatform.notification.slack.SlackMessageStore;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

/**
 * Backlog #42. This class had no test file at all before this fix.
 * Covers both the pre-existing "process outbox entries independently"
 * contract and the actual regression coverage for this fix: the catch
 * block's failure handling now delegates through
 * {@link NotificationPersistenceService} instead of calling
 * {@code queueRepository.save(...)} directly, with the same defensive
 * wrapper shape as {@code EscalationScheduler}'s
 * {@code recordFailedAttemptSafely} (backlog #41) — a secondary failure
 * recording the failure must not abort the rest of the batch.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationScheduler")
class NotificationSchedulerTest {

    @Mock private NotificationQueueRepository queueRepository;
    @Mock private NotificationService notificationService;
    @Mock private NotificationPersistenceService persistenceService;
    @Mock private SlackMessageStore messageStore;

    private NotificationScheduler scheduler;

    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        final NotificationSchedulerProperties properties =
                new NotificationSchedulerProperties(Duration.ofSeconds(30), Duration.ofDays(7), Duration.ofMinutes(10), Duration.ofMinutes(3), 200);
        scheduler = new NotificationScheduler(
                queueRepository, notificationService, persistenceService,
                messageStore, properties);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private NotificationQueueEntry buildPendingEntry() {
        return NotificationQueueEntry.pending(
                UUID.randomUUID(), TENANT_ID, "IncidentOpenedEvent",
                Severity.CRITICAL, "High CPU");
    }

    /**
     * Backlog #0-19: an oncall-service outage is not "nobody on call". The
     * entry stays PENDING and is retried for a bounded window; after it the
     * entry is parked as UNDELIVERABLE (which also tells the operator).
     */
    /**
     * Backlog #0-21: the router only lets an auth-service outage through when
     * Slack was the contact's sole reachable channel. The scheduler then applies
     * the same retry window as the on-call lookup, with its own give-up reason.
     */
    @Nested
    @DisplayName("auth-service unavailable, Slack the only channel (backlog #0-21)")
    class SlackWorkspaceUnavailable {

        private void failSlackLookupFor(NotificationQueueEntry entry) {
            willThrow(new SlackWorkspaceLookupUnavailableException("down", new RuntimeException()))
                    .given(notificationService).processEntry(entry);
        }

        @Test
        @DisplayName("inside the retry window the entry is left PENDING and the first failure recorded")
        void staysPendingInsideTheWindow() {
            final NotificationQueueEntry entry = buildPendingEntry();
            given(queueRepository.findPendingOlderThan(any(), any())).willReturn(List.of(entry));
            failSlackLookupFor(entry);

            scheduler.processPendingNotifications();

            then(persistenceService).should().recordLookupFailure(entry);
            then(notificationService).should(never()).markUndeliverable(any(), any());
            then(persistenceService).should(never()).markFailed(any(), any());
        }

        @Test
        @DisplayName("past the retry window the entry is parked as UNDELIVERABLE (SLACK_WORKSPACE_UNAVAILABLE), not ONCALL_UNAVAILABLE")
        void undeliverableAfterTheWindow() {
            final NotificationQueueEntry entry = buildPendingEntry();
            ReflectionTestUtils.setField(entry, "firstLookupFailureAt",
                    Instant.now().minus(Duration.ofMinutes(11)));
            given(queueRepository.findPendingOlderThan(any(), any())).willReturn(List.of(entry));
            failSlackLookupFor(entry);

            scheduler.processPendingNotifications();

            then(notificationService).should()
                    .markUndeliverable(entry, UndeliverableReason.SLACK_WORKSPACE_UNAVAILABLE);
            then(persistenceService).should(never()).markFailed(any(), any());
        }
    }

    @Nested
    @DisplayName("oncall-service unavailable (backlog #0-19)")
    class OncallUnavailable {

        /** An entry created long ago, whose lookup first failed {@code failingFor} ago (null: never). */
        private NotificationQueueEntry entry(Duration createdAgo, Duration failingFor) {
            final NotificationQueueEntry entry = buildPendingEntry();
            ReflectionTestUtils.setField(entry, "createdAt", Instant.now().minus(createdAgo));
            if (failingFor != null) {
                ReflectionTestUtils.setField(entry, "firstLookupFailureAt",
                        Instant.now().minus(failingFor));
            }
            return entry;
        }

        private void failLookupFor(NotificationQueueEntry entry) {
            willThrow(new OncallLookupUnavailableException("down", new RuntimeException()))
                    .given(notificationService).processEntry(entry);
        }

        @Test
        @DisplayName("records the first failed lookup on the entry")
        void recordsTheFirstFailure() {
            final NotificationQueueEntry entry = entry(Duration.ofMinutes(1), null);
            given(queueRepository.findPendingOlderThan(any(), any())).willReturn(List.of(entry));
            failLookupFor(entry);

            scheduler.processPendingNotifications();

            then(persistenceService).should().recordLookupFailure(entry);
        }

        @Test
        @DisplayName("does not write the failure again once the first one is recorded")
        void doesNotRerecordTheFirstFailure() {
            final NotificationQueueEntry entry = entry(Duration.ofMinutes(5), Duration.ofMinutes(2));
            given(queueRepository.findPendingOlderThan(any(), any())).willReturn(List.of(entry));
            failLookupFor(entry);

            scheduler.processPendingNotifications();

            then(persistenceService).should(never()).recordLookupFailure(any());
        }

        @Test
        @DisplayName("inside the retry window the entry is left PENDING: not FAILED, not UNDELIVERABLE")
        void staysPendingInsideTheWindow() {
            final NotificationQueueEntry entry = entry(Duration.ofMinutes(2), Duration.ofMinutes(2));
            given(queueRepository.findPendingOlderThan(any(), any())).willReturn(List.of(entry));
            failLookupFor(entry);

            scheduler.processPendingNotifications();

            then(notificationService).should(never()).markUndeliverable(any(), any());
            then(persistenceService).should(never()).markFailed(any(), any());
        }

        @Test
        @DisplayName("an entry older than the window still gets its retries: the window runs from the first failed lookup, not from creation")
        void oldEntryStillGetsRetries() {
            // created 2 hours ago (a restart, a long outage), first failure just now
            final NotificationQueueEntry entry = entry(Duration.ofHours(2), null);
            given(queueRepository.findPendingOlderThan(any(), any())).willReturn(List.of(entry));
            failLookupFor(entry);

            scheduler.processPendingNotifications();

            then(notificationService).should(never()).markUndeliverable(any(), any());
        }

        @Test
        @DisplayName("past the retry window since the first failure the entry is parked as UNDELIVERABLE (ONCALL_UNAVAILABLE)")
        void undeliverableAfterTheWindow() {
            final NotificationQueueEntry entry = entry(Duration.ofHours(1), Duration.ofMinutes(11));
            given(queueRepository.findPendingOlderThan(any(), any())).willReturn(List.of(entry));
            failLookupFor(entry);

            scheduler.processPendingNotifications();

            then(notificationService).should()
                    .markUndeliverable(entry, UndeliverableReason.ONCALL_UNAVAILABLE);
            then(persistenceService).should(never()).markFailed(any(), any());
        }

        @Test
        @DisplayName("keeps processing the rest of the batch when parking the entry itself fails")
        void continuesWhenParkingFails() {
            final NotificationQueueEntry old = entry(Duration.ofHours(1), Duration.ofMinutes(11));
            final NotificationQueueEntry other = buildPendingEntry();
            given(queueRepository.findPendingOlderThan(any(), any())).willReturn(List.of(old, other));
            failLookupFor(old);
            willThrow(new RuntimeException("db down"))
                    .given(notificationService).markUndeliverable(any(), any());

            scheduler.processPendingNotifications();

            then(notificationService).should().processEntry(other);
        }

        @Test
        @DisplayName("keeps the entry PENDING when recording the failure itself fails")
        void recordingFailureIsNotFatal() {
            final NotificationQueueEntry entry = entry(Duration.ofMinutes(1), null);
            given(queueRepository.findPendingOlderThan(any(), any())).willReturn(List.of(entry));
            failLookupFor(entry);
            willThrow(new RuntimeException("db down"))
                    .given(persistenceService).recordLookupFailure(any());

            scheduler.processPendingNotifications();

            then(notificationService).should(never()).markUndeliverable(any(), any());
            then(persistenceService).should(never()).markFailed(any(), any());
        }
    }

    @Nested
    @DisplayName("batch and budget configuration")
    class BatchAndBudgetConfiguration {

        private NotificationSchedulerProperties props(Duration budget) {
            return new NotificationSchedulerProperties(Duration.ofSeconds(30), Duration.ofDays(7),
                    Duration.ofMinutes(10), budget, 200);
        }

        @Test
        @DisplayName("loads at most batch-size entries, from the first page (oldest first)")
        void loadsOneCappedPage() {
            given(queueRepository.findPendingOlderThan(any(), any())).willReturn(List.of());

            scheduler.processPendingNotifications();

            final org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> page =
                    org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
            then(queueRepository).should().findPendingOlderThan(any(), page.capture());
            org.assertj.core.api.Assertions.assertThat(page.getValue())
                    .isEqualTo(org.springframework.data.domain.PageRequest.of(0, 200));
        }

        @Test
        @DisplayName("rejects, at startup, a processing budget that could outlive the ShedLock")
        void rejectsABudgetLongerThanTheLock() {
            for (final Duration bad : List.of(Duration.ZERO, Duration.ofMinutes(-1),
                    Duration.ofMinutes(4), Duration.ofMinutes(5))) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> new NotificationScheduler(
                                queueRepository, notificationService, persistenceService,
                                messageStore, props(bad)))
                        .as("budget %s", bad)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("accepts a budget with margin below the lock")
        void acceptsABudgetBelowTheLock() {
            org.assertj.core.api.Assertions.assertThatCode(() -> new NotificationScheduler(
                            queueRepository, notificationService, persistenceService,
                            messageStore, props(Duration.ofMinutes(3))))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("processing budget")
    class ProcessingBudget {

        @Test
        @DisplayName("stops after the budget is used up but always processes at least one entry")
        void stopsWhenTheBudgetIsUsedUp() {
            final NotificationScheduler tight = new NotificationScheduler(
                    queueRepository, notificationService, persistenceService, messageStore,
                    new NotificationSchedulerProperties(Duration.ofSeconds(30), Duration.ofDays(7),
                            Duration.ofMinutes(10), Duration.ofNanos(1), 200));
            final NotificationQueueEntry first = buildPendingEntry();
            final NotificationQueueEntry second = buildPendingEntry();
            final NotificationQueueEntry third = buildPendingEntry();
            given(queueRepository.findPendingOlderThan(any(), any()))
                    .willReturn(List.of(first, second, third));

            tight.processPendingNotifications();

            then(notificationService).should().processEntry(first);
            then(notificationService).should(never()).processEntry(second);
            then(notificationService).should(never()).processEntry(third);
        }

        @Test
        @DisplayName("processes every entry when the budget is not exhausted")
        void processesEverythingInsideTheBudget() {
            final NotificationQueueEntry first = buildPendingEntry();
            final NotificationQueueEntry second = buildPendingEntry();
            given(queueRepository.findPendingOlderThan(any(), any())).willReturn(List.of(first, second));

            scheduler.processPendingNotifications();

            then(notificationService).should().processEntry(first);
            then(notificationService).should().processEntry(second);
        }
    }

    @Nested
    @DisplayName("processPendingNotifications")
    class ProcessPendingNotifications {

        @Test
        @DisplayName("does nothing when there are no PENDING entries")
        void doesNothingWhenNoPendingEntries() {
            given(queueRepository.findPendingOlderThan(any(), any())).willReturn(List.of());

            scheduler.processPendingNotifications();

            then(notificationService).shouldHaveNoInteractions();
            then(persistenceService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("processes each due entry via notificationService")
        void processesEachDueEntry() {
            final NotificationQueueEntry entry = buildPendingEntry();
            given(queueRepository.findPendingOlderThan(any(), any())).willReturn(List.of(entry));

            scheduler.processPendingNotifications();

            then(notificationService).should().processEntry(entry);
        }

        @Test
        @DisplayName("continues processing remaining entries if one throws")
        void continuesAfterOneEntryFails() {
            final NotificationQueueEntry failing = buildPendingEntry();
            final NotificationQueueEntry normal = buildPendingEntry();
            given(queueRepository.findPendingOlderThan(any(), any()))
                    .willReturn(List.of(failing, normal));
            willThrow(new RuntimeException("oncall-service unreachable"))
                    .given(notificationService).processEntry(failing);

            scheduler.processPendingNotifications();

            then(notificationService).should().processEntry(normal);
        }

        /**
         * The actual regression test for backlog #42. Verifies the catch
         * block delegates through persistenceService.markFailed(...)
         * instead of touching queueRepository directly.
         */
        @Test
        @DisplayName("marks a failing entry FAILED via persistenceService, " +
                "not by calling queueRepository directly")
        void marksFailedViaPersistenceServiceOnFailure() {
            final NotificationQueueEntry entry = buildPendingEntry();
            given(queueRepository.findPendingOlderThan(any(), any())).willReturn(List.of(entry));
            willThrow(new RuntimeException("oncall-service unreachable"))
                    .given(notificationService).processEntry(entry);

            scheduler.processPendingNotifications();

            then(persistenceService).should().markFailed(entry, "oncall-service unreachable");
            then(queueRepository).should(never()).save(any());
        }

        /**
         * Mirrors EscalationScheduler's continuesBatchEvenIfRecordFailedAttemptThrows
         * (backlog #41) — if recording the failure itself ALSO fails, that
         * secondary failure must not abort processing of the rest of the
         * batch.
         */
        @Test
        @DisplayName("continues processing remaining entries even if " +
                "persistenceService.markFailed itself throws")
        void continuesBatchEvenIfMarkFailedThrows() {
            final NotificationQueueEntry failing = buildPendingEntry();
            final NotificationQueueEntry normal = buildPendingEntry();
            given(queueRepository.findPendingOlderThan(any(), any()))
                    .willReturn(List.of(failing, normal));

            willThrow(new RuntimeException("oncall-service unreachable"))
                    .given(notificationService).processEntry(failing);
            willThrow(new RuntimeException("DB also unavailable right now"))
                    .given(persistenceService).markFailed(any(), any());

            scheduler.processPendingNotifications();

            then(notificationService).should().processEntry(normal);
        }

        @Test
        @DisplayName("sets and clears TenantContext per entry")
        void setsAndClearsTenantContextPerEntry() {
            final NotificationQueueEntry entry = buildPendingEntry();
            given(queueRepository.findPendingOlderThan(any(), any())).willReturn(List.of(entry));

            scheduler.processPendingNotifications();

            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("clears TenantContext even when processing throws")
        void clearsTenantContextOnFailure() {
            final NotificationQueueEntry entry = buildPendingEntry();
            given(queueRepository.findPendingOlderThan(any(), any())).willReturn(List.of(entry));
            willThrow(new RuntimeException("oncall-service unreachable"))
                    .given(notificationService).processEntry(entry);

            scheduler.processPendingNotifications();

            assertThat(TenantContext.getOrNull()).isNull();
        }
    }

    @Nested
    @DisplayName("cleanupOldSlackMessageTs")
    class CleanupOldSlackMessageTs {

        @Test
        @DisplayName("deletes slack message ts rows older than the retention threshold")
        void deletesOldRows() {
            given(messageStore.deleteOlderThan(any())).willReturn(3);

            scheduler.cleanupOldSlackMessageTs();

            then(messageStore).should().deleteOlderThan(any(Instant.class));
        }
    }
}