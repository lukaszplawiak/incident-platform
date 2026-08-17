package com.incidentplatform.escalation.scheduler;

import com.incidentplatform.escalation.client.OncallServiceClient;
import com.incidentplatform.escalation.domain.EscalationTask;
import com.incidentplatform.escalation.repository.EscalationTaskRepository;
import com.incidentplatform.escalation.service.EscalationService;
import com.incidentplatform.escalation.service.EscalationTaskPersistenceService;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.IncidentEscalatedEvent;
import com.incidentplatform.shared.events.IncidentEventKafkaSender;
import com.incidentplatform.shared.events.IncidentEventTypes;
import com.incidentplatform.shared.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Backlog #39 note: EscalationScheduler no longer calls
 * taskRepository.saveAndFlush(...) directly — it delegates to
 * EscalationTaskPersistenceService.markEscalated(task), a void method.
 * Tests that previously stubbed/verified taskRepository.saveAndFlush(...)
 * now stub/verify persistenceService.markEscalated(...) instead. Since
 * markEscalated() is void, success requires no stub at all (Mockito's
 * default behavior for an unstubbed void method is a no-op) — only
 * failure paths need an explicit willThrow(...).given(persistenceService)
 * stub.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EscalationScheduler")
class EscalationSchedulerTest {

    @Mock
    private EscalationTaskRepository taskRepository;

    @Mock
    private EscalationTaskPersistenceService persistenceService;

    @Mock
    private IncidentEventKafkaSender kafkaSender;

    @Mock
    private EscalationService escalationService;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    @Mock
    private OncallServiceClient oncallServiceClient;

    private EscalationScheduler scheduler;

    private static final String TENANT_ID = "test-tenant";
    private static final int BATCH_SIZE = 100;

    @BeforeEach
    void setUp() {
        scheduler = new EscalationScheduler(
                taskRepository,
                persistenceService,
                kafkaSender,
                escalationService,
                auditEventPublisher,
                oncallServiceClient,
                BATCH_SIZE);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("checkAndEscalate")
    class CheckAndEscalate {

        @Test
        @DisplayName("should escalate level 1 task and schedule level 2")
        void shouldEscalateLevel1AndScheduleLevel2() {
            // given
            final EscalationTask task = buildOverdueTask(1);
            given(taskRepository.findDueForEscalation(any(), any()))
                    .willReturn(List.of(task));

            // when
            scheduler.checkAndEscalate();

            // then
            then(kafkaSender).should().send(
                    any(IncidentEscalatedEvent.class),
                    eq(IncidentEventTypes.INCIDENT_ESCALATED));
            // persistenceService is mocked here — the real status mutation
            // happens inside its real implementation, tested separately in
            // EscalationTaskPersistenceServiceTest.marksEscalatedAndPersists().
            // This test verifies the scheduler calls it with the right task.
            then(persistenceService).should().markEscalated(task);

            then(escalationService).should().scheduleLevel2Escalation(
                    task.getIncidentId(), TENANT_ID, task.getTeamId(),
                    task.getSeverity(), task.getTitle());
        }

        @Test
        @DisplayName("should escalate level 2 task without scheduling level 3")
        void shouldEscalateLevel2WithoutSchedulingLevel3() {
            // given
            final EscalationTask task = buildOverdueTask(2);
            given(taskRepository.findDueForEscalation(any(), any()))
                    .willReturn(List.of(task));

            // when
            scheduler.checkAndEscalate();

            // then
            then(kafkaSender).should().send(
                    any(IncidentEscalatedEvent.class),
                    eq(IncidentEventTypes.INCIDENT_ESCALATED));
            // See shouldEscalateLevel1AndScheduleLevel2's comment — the real
            // status mutation happens inside the (here, mocked) persistence
            // service, tested separately.
            then(persistenceService).should().markEscalated(task);

            then(escalationService).should(never())
                    .scheduleLevel2Escalation(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should do nothing when no tasks are due")
        void shouldDoNothingWhenNoTasksDue() {
            // given
            given(taskRepository.findDueForEscalation(any(), any()))
                    .willReturn(List.of());

            // when
            scheduler.checkAndEscalate();

            // then
            then(kafkaSender).should(never()).send(any(), any());
            then(persistenceService).should(never()).markEscalated(any());
        }

        @Test
        @DisplayName("should persist state before publishing to Kafka — prevents duplicate notifications")
        void shouldPersistBeforePublishingToKafka() {
            // given
            final EscalationTask task = buildOverdueTask(1);
            given(taskRepository.findDueForEscalation(any(), any()))
                    .willReturn(List.of(task));

            // when
            scheduler.checkAndEscalate();

            // then — markEscalated() must happen strictly before kafkaSender.send().
            // If this order were reversed and persistenceService.markEscalated()
            // threw after kafkaSender.send(), the notification would have already
            // gone out for a task whose DB state was never actually persisted.
            final InOrder order = inOrder(persistenceService, kafkaSender);
            order.verify(persistenceService).markEscalated(any(EscalationTask.class));
            order.verify(kafkaSender).send(
                    any(IncidentEscalatedEvent.class),
                    eq(IncidentEventTypes.INCIDENT_ESCALATED));
        }

        @Test
        @DisplayName("should continue escalating other tasks if one Kafka send fails")
        void shouldContinueAfterOneKafkaSendFailure() {
            // given
            final EscalationTask task1 = buildOverdueTask(1);
            final EscalationTask task2 = buildOverdueTask(1);

            given(taskRepository.findDueForEscalation(any(), any()))
                    .willReturn(List.of(task1, task2));

            // First kafkaSender.send() (task1) throws after the DB state has
            // already been persisted. Second call (task2) succeeds.
            // NOTE: with persist-first ordering, task1 is saved as ESCALATED
            // in DB even though its Kafka send failed — it will NOT be retried
            // by the scheduler. This is the at-most-once trade-off documented
            // in EscalationScheduler (see Outbox Pattern TODO).
            willThrow(new RuntimeException("Kafka unavailable"))
                    .willDoNothing()
                    .given(kafkaSender).send(any(), any());

            // when
            scheduler.checkAndEscalate();

            // then — both tasks attempted despite task1's Kafka failure
            then(kafkaSender).should(times(2)).send(any(), any());
            // task2 was also persisted
            then(persistenceService).should(times(2)).markEscalated(any());
        }

        @Test
        @DisplayName("should NOT send Kafka event when DB save fails — prevents stale task from being re-escalated")
        void shouldNotSendKafkaEventWhenDbSaveFails() {
            // given
            final EscalationTask task = buildOverdueTask(1);
            given(taskRepository.findDueForEscalation(any(), any()))
                    .willReturn(List.of(task));

            // Persistence throws — simulates connection pool exhaustion or timeout
            willThrow(new RuntimeException("DB connection lost"))
                    .given(persistenceService).markEscalated(any());

            // when
            scheduler.checkAndEscalate();

            // then — kafkaSender.send() must NOT be called.
            // Since the write failed, findDueForEscalation() will return this
            // task again on the next tick and escalation will be retried
            // cleanly — no duplicate notification sent.
            then(kafkaSender).should(never()).send(any(), any());
        }

        /**
         * The actual regression test for backlog #38. Simulates the race
         * this fix targets: EscalationService.cancelEscalation() (a
         * different thread — the Kafka listener, reacting to an
         * IncidentAcknowledgedEvent) concurrently modified this task
         * between findDueForEscalation() reading it and this scheduler
         * tick trying to save it — surfacing as
         * OptimisticLockingFailureException from
         * persistenceService.markEscalated(). Unlike a generic DB failure
         * (shouldNotSendKafkaEventWhenDbSaveFails above), this must be
         * recognized specifically: no error should be logged as if
         * something went wrong (the cancellation is the expected, correct
         * outcome), and — critically — no Kafka event should be sent for
         * a task that was just cancelled.
         */
        @Test
        @DisplayName("should skip the task (no Kafka event, no error) when " +
                "markEscalated hits an optimistic lock conflict — " +
                "concurrently cancelled by an ACK")
        void shouldSkipTaskOnOptimisticLockConflict() {
            // given
            final EscalationTask task = buildOverdueTask(1);
            given(taskRepository.findDueForEscalation(any(), any()))
                    .willReturn(List.of(task));

            willThrow(new OptimisticLockingFailureException(
                    "Row was updated or deleted by another transaction"))
                    .given(persistenceService).markEscalated(any());

            // when
            scheduler.checkAndEscalate();

            // then — no notification sent for a task that was just cancelled
            then(kafkaSender).should(never()).send(any(), any());
        }

        @Test
        @DisplayName("should continue processing other tasks after one hits " +
                "an optimistic lock conflict")
        void shouldContinueProcessingAfterOneOptimisticLockConflict() {
            // given
            final EscalationTask conflicted = buildOverdueTask(1);
            final EscalationTask normal = buildOverdueTask(1);
            given(taskRepository.findDueForEscalation(any(), any()))
                    .willReturn(List.of(conflicted, normal));

            willThrow(new OptimisticLockingFailureException(
                    "Row was updated or deleted by another transaction"))
                    .willDoNothing()
                    .given(persistenceService).markEscalated(any());

            // when
            scheduler.checkAndEscalate();

            // then — the second (unaffected) task still gets escalated normally
            then(kafkaSender).should(times(1)).send(any(), any());
        }

        @Test
        @DisplayName("should send IncidentEscalatedEvent with correct fields")
        void shouldSendEventWithCorrectFields() {
            // given
            final EscalationTask task = buildOverdueTask(1);
            given(taskRepository.findDueForEscalation(any(), any()))
                    .willReturn(List.of(task));

            // when
            scheduler.checkAndEscalate();

            // then
            final ArgumentCaptor<IncidentEscalatedEvent> eventCaptor =
                    ArgumentCaptor.forClass(IncidentEscalatedEvent.class);
            then(kafkaSender).should().send(
                    eventCaptor.capture(), eq(IncidentEventTypes.INCIDENT_ESCALATED));

            final IncidentEscalatedEvent event = eventCaptor.getValue();
            assertThat(event.incidentId()).isEqualTo(task.getIncidentId());
            assertThat(event.tenantId()).isEqualTo(TENANT_ID);
            assertThat(event.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(event.escalationLevel()).isEqualTo(1);
        }

        @Test
        @DisplayName("should send with INCIDENT_ESCALATED event type")
        void shouldSendWithEscalatedEventType() {
            // given
            final EscalationTask task = buildOverdueTask(1);
            given(taskRepository.findDueForEscalation(any(), any()))
                    .willReturn(List.of(task));

            // when
            scheduler.checkAndEscalate();

            // then — X-Event-Type header value is set via the eventType argument;
            // IncidentEventKafkaSender attaches it as a Kafka header so
            // notification-service can route IncidentEscalatedEvent to
            // EMAIL/SLACK/SMS without inspecting the payload.
            then(kafkaSender).should().send(
                    any(IncidentEscalatedEvent.class),
                    eq(IncidentEventTypes.INCIDENT_ESCALATED));
        }

        /**
         * Regression test for backlog #39's batch-size cap. Verifies the
         * limit configured in the constructor is actually passed through
         * to the repository query as a Pageable, rather than the
         * repository being called with an unbounded request.
         */
        @Test
        @DisplayName("should cap the batch size passed to findDueForEscalation")
        void shouldCapBatchSize() {
            // given
            given(taskRepository.findDueForEscalation(any(), any()))
                    .willReturn(List.of());

            // when
            scheduler.checkAndEscalate();

            // then
            final ArgumentCaptor<org.springframework.data.domain.Pageable> pageableCaptor =
                    ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
            then(taskRepository).should()
                    .findDueForEscalation(any(), pageableCaptor.capture());

            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(BATCH_SIZE);
        }
    }

    @Nested
    @DisplayName("TenantContext handling")
    class TenantContextHandling {

        @Test
        @DisplayName("should clear TenantContext after processing all due tasks")
        void shouldClearTenantContextAfterProcessing() {
            // given
            final EscalationTask task = buildOverdueTask(1);
            given(taskRepository.findDueForEscalation(any(), any()))
                    .willReturn(List.of(task));

            // when
            scheduler.checkAndEscalate();

            // then — no tenant context leaks out of the scheduler run
            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("should clear TenantContext even when Kafka send throws")
        void shouldClearTenantContextOnFailure() {
            // given
            final EscalationTask task = buildOverdueTask(1);
            given(taskRepository.findDueForEscalation(any(), any()))
                    .willReturn(List.of(task));
            willThrow(new RuntimeException("Kafka unavailable"))
                    .given(kafkaSender).send(any(), any());

            // when
            scheduler.checkAndEscalate();

            // then
            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("should not leak tenantId between consecutive tasks " +
                "belonging to different tenants")
        void shouldNotLeakTenantIdBetweenTasks() {
            // given — two overdue tasks from two different tenants in the same batch
            final EscalationTask taskTenantA = buildOverdueTaskForTenant("tenant-a");
            final EscalationTask taskTenantB = buildOverdueTaskForTenant("tenant-b");

            given(taskRepository.findDueForEscalation(any(), any()))
                    .willReturn(List.of(taskTenantA, taskTenantB));

            // Capture TenantContext at the moment each Kafka send happens — the
            // most direct way to verify the context was correctly scoped to
            // each task during its own processing window.
            // send() returns void, so the BDD-style stub is willAnswer(...).given(...)
            // rather than given(...).willAnswer(...) (which expects a non-void return type).
            final List<String> observedTenants = new ArrayList<>();
            willAnswer(invocation -> {
                observedTenants.add(TenantContext.getOrNull());
                return null;
            }).given(kafkaSender).send(any(), any());

            // when
            scheduler.checkAndEscalate();

            // then
            assertThat(observedTenants).containsExactly("tenant-a", "tenant-b");
        }
    }

    private EscalationTask buildOverdueTask(int level) {
        return buildOverdueTaskForTenantAndLevel(TENANT_ID, level);
    }

    private EscalationTask buildOverdueTaskForTenant(String tenantId) {
        return buildOverdueTaskForTenantAndLevel(tenantId, 1);
    }

    private EscalationTask buildOverdueTaskForTenantAndLevel(String tenantId, int level) {
        final Instant openedAt = Instant.now().minusSeconds(60 * 60L);
        if (level == 1) {
            return EscalationTask.createLevel1(
                    UUID.randomUUID(), tenantId, UUID.randomUUID(), openedAt,
                    Severity.CRITICAL, "High CPU Usage");
        } else {
            return EscalationTask.createLevel2(
                    UUID.randomUUID(), tenantId, UUID.randomUUID(), openedAt,
                    Severity.CRITICAL, "High CPU Usage");
        }
    }
}