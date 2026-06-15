package com.incidentplatform.escalation.scheduler;

import com.incidentplatform.escalation.domain.EscalationTask;
import com.incidentplatform.escalation.domain.EscalationTaskStatus;
import com.incidentplatform.escalation.repository.EscalationTaskRepository;
import com.incidentplatform.escalation.service.EscalationService;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.IncidentEscalatedEvent;
import com.incidentplatform.shared.events.IncidentEventKafkaSender;
import com.incidentplatform.shared.events.IncidentEventTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("EscalationScheduler")
class EscalationSchedulerTest {

    @Mock
    private EscalationTaskRepository taskRepository;

    @Mock
    private IncidentEventKafkaSender kafkaSender;

    @Mock
    private EscalationService escalationService;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    private EscalationScheduler scheduler;

    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        scheduler = new EscalationScheduler(
                taskRepository,
                kafkaSender,
                escalationService,
                auditEventPublisher);
    }

    @Nested
    @DisplayName("checkAndEscalate")
    class CheckAndEscalate {

        @Test
        @DisplayName("should escalate level 1 task and schedule level 2")
        void shouldEscalateLevel1AndScheduleLevel2() {
            // given
            final EscalationTask task = buildOverdueTask(1);
            given(taskRepository.findDueForEscalation(any()))
                    .willReturn(List.of(task));
            given(taskRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            scheduler.checkAndEscalate();

            // then
            then(kafkaSender).should().send(
                    any(IncidentEscalatedEvent.class),
                    eq(IncidentEventTypes.INCIDENT_ESCALATED));
            assertThat(task.getStatus()).isEqualTo(EscalationTaskStatus.ESCALATED);

            then(escalationService).should().scheduleLevel2Escalation(
                    task.getIncidentId(), TENANT_ID,
                    task.getSeverity(), task.getTitle());
        }

        @Test
        @DisplayName("should escalate level 2 task without scheduling level 3")
        void shouldEscalateLevel2WithoutSchedulingLevel3() {
            // given
            final EscalationTask task = buildOverdueTask(2);
            given(taskRepository.findDueForEscalation(any()))
                    .willReturn(List.of(task));
            given(taskRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            scheduler.checkAndEscalate();

            // then
            then(kafkaSender).should().send(
                    any(IncidentEscalatedEvent.class),
                    eq(IncidentEventTypes.INCIDENT_ESCALATED));
            assertThat(task.getStatus()).isEqualTo(EscalationTaskStatus.ESCALATED);

            then(escalationService).should(never())
                    .scheduleLevel2Escalation(any(), any(), any(), any());
        }

        @Test
        @DisplayName("should do nothing when no tasks are due")
        void shouldDoNothingWhenNoTasksDue() {
            // given
            given(taskRepository.findDueForEscalation(any()))
                    .willReturn(List.of());

            // when
            scheduler.checkAndEscalate();

            // then
            then(kafkaSender).should(never()).send(any(), any());
            then(taskRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("should persist state before publishing to Kafka — prevents duplicate notifications")
        void shouldPersistBeforePublishingToKafka() {
            // given
            final EscalationTask task = buildOverdueTask(1);
            given(taskRepository.findDueForEscalation(any()))
                    .willReturn(List.of(task));
            given(taskRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            scheduler.checkAndEscalate();

            // then — save() must happen strictly before kafkaSender.send().
            // If this order were reversed and taskRepository.save() threw after
            // kafkaSender.send(), @Transactional would roll back the DB state but
            // the Kafka event would already be in-flight — causing duplicate
            // on-call notifications on the next scheduler tick.
            final InOrder order = inOrder(taskRepository, kafkaSender);
            order.verify(taskRepository).save(any(EscalationTask.class));
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

            given(taskRepository.findDueForEscalation(any()))
                    .willReturn(List.of(task1, task2));
            given(taskRepository.save(any())).willAnswer(i -> i.getArgument(0));

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
            // task2 was also saved
            then(taskRepository).should(times(2)).save(any());
        }

        @Test
        @DisplayName("should NOT send Kafka event when DB save fails — prevents stale task from being re-escalated")
        void shouldNotSendKafkaEventWhenDbSaveFails() {
            // given
            final EscalationTask task = buildOverdueTask(1);
            given(taskRepository.findDueForEscalation(any()))
                    .willReturn(List.of(task));

            // DB save throws — simulates connection pool exhaustion or timeout
            willThrow(new RuntimeException("DB connection lost"))
                    .given(taskRepository).save(any());

            // when
            scheduler.checkAndEscalate();

            // then — kafkaSender.send() must NOT be called.
            // Since DB rolled back, findDueForEscalation() will return this task
            // again on the next tick and escalation will be retried cleanly —
            // no duplicate notification sent.
            then(kafkaSender).should(never()).send(any(), any());
        }

        @Test
        @DisplayName("should send IncidentEscalatedEvent with correct fields")
        void shouldSendEventWithCorrectFields() {
            // given
            final EscalationTask task = buildOverdueTask(1);
            given(taskRepository.findDueForEscalation(any()))
                    .willReturn(List.of(task));
            given(taskRepository.save(any())).willAnswer(i -> i.getArgument(0));

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
            given(taskRepository.findDueForEscalation(any()))
                    .willReturn(List.of(task));
            given(taskRepository.save(any())).willAnswer(i -> i.getArgument(0));

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
    }

    private EscalationTask buildOverdueTask(int level) {
        final Instant openedAt = Instant.now().minusSeconds(60 * 60L);
        if (level == 1) {
            return EscalationTask.createLevel1(
                    UUID.randomUUID(), TENANT_ID, openedAt,
                    Severity.CRITICAL, "High CPU Usage");
        } else {
            return EscalationTask.createLevel2(
                    UUID.randomUUID(), TENANT_ID, openedAt,
                    Severity.CRITICAL, "High CPU Usage");
        }
    }
}