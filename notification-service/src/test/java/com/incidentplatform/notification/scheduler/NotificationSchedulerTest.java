package com.incidentplatform.notification.scheduler;

import com.incidentplatform.notification.config.NotificationSchedulerProperties;
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
                new NotificationSchedulerProperties(Duration.ofSeconds(30), Duration.ofDays(7));
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

    @Nested
    @DisplayName("processPendingNotifications")
    class ProcessPendingNotifications {

        @Test
        @DisplayName("does nothing when there are no PENDING entries")
        void doesNothingWhenNoPendingEntries() {
            given(queueRepository.findPendingOlderThan(any())).willReturn(List.of());

            scheduler.processPendingNotifications();

            then(notificationService).shouldHaveNoInteractions();
            then(persistenceService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("processes each due entry via notificationService")
        void processesEachDueEntry() {
            final NotificationQueueEntry entry = buildPendingEntry();
            given(queueRepository.findPendingOlderThan(any())).willReturn(List.of(entry));

            scheduler.processPendingNotifications();

            then(notificationService).should().processEntry(entry);
        }

        @Test
        @DisplayName("continues processing remaining entries if one throws")
        void continuesAfterOneEntryFails() {
            final NotificationQueueEntry failing = buildPendingEntry();
            final NotificationQueueEntry normal = buildPendingEntry();
            given(queueRepository.findPendingOlderThan(any()))
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
            given(queueRepository.findPendingOlderThan(any())).willReturn(List.of(entry));
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
            given(queueRepository.findPendingOlderThan(any()))
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
            given(queueRepository.findPendingOlderThan(any())).willReturn(List.of(entry));

            scheduler.processPendingNotifications();

            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("clears TenantContext even when processing throws")
        void clearsTenantContextOnFailure() {
            final NotificationQueueEntry entry = buildPendingEntry();
            given(queueRepository.findPendingOlderThan(any())).willReturn(List.of(entry));
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