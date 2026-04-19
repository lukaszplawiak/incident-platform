package com.incidentplatform.escalation.service;

import com.incidentplatform.escalation.domain.EscalationTask;
import com.incidentplatform.escalation.repository.EscalationTaskRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("EscalationService")
class EscalationServiceTest {

    @Mock
    private EscalationTaskRepository taskRepository;

    private EscalationService escalationService;

    private static final UUID INCIDENT_ID = UUID.randomUUID();
    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        escalationService = new EscalationService(taskRepository);
    }

    @Nested
    @DisplayName("scheduleEscalation")
    class ScheduleEscalation {

        @Test
        @DisplayName("should create level 1 escalation task for new incident")
        void shouldCreateLevel1TaskForNewIncident() {
            // given
            given(taskRepository.existsByIncidentIdAndEscalationLevel(
                    INCIDENT_ID, 1)).willReturn(false);
            given(taskRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            escalationService.scheduleEscalation(
                    INCIDENT_ID, TENANT_ID, Instant.now(),
                    "CRITICAL", "High CPU Usage");

            // then
            final ArgumentCaptor<EscalationTask> captor =
                    ArgumentCaptor.forClass(EscalationTask.class);
            then(taskRepository).should().save(captor.capture());

            final EscalationTask saved = captor.getValue();
            assertThat(saved.getIncidentId()).isEqualTo(INCIDENT_ID);
            assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(saved.getStatus()).isEqualTo("PENDING");
            assertThat(saved.getSeverity()).isEqualTo("CRITICAL");
            assertThat(saved.getEscalationLevel()).isEqualTo(1);
        }

        @Test
        @DisplayName("should schedule CRITICAL escalation after 5 minutes")
        void shouldScheduleCriticalEscalationAfter5Minutes() {
            // given
            final Instant openedAt = Instant.now();
            given(taskRepository.existsByIncidentIdAndEscalationLevel(
                    INCIDENT_ID, 1)).willReturn(false);
            given(taskRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            escalationService.scheduleEscalation(
                    INCIDENT_ID, TENANT_ID, openedAt, "CRITICAL", "Test");

            // then
            final ArgumentCaptor<EscalationTask> captor =
                    ArgumentCaptor.forClass(EscalationTask.class);
            then(taskRepository).should().save(captor.capture());

            assertThat(captor.getValue().getScheduledEscalationAt())
                    .isEqualTo(openedAt.plusSeconds(5 * 60L));
        }

        @Test
        @DisplayName("should schedule HIGH escalation after 15 minutes")
        void shouldScheduleHighEscalationAfter15Minutes() {
            // given
            final Instant openedAt = Instant.now();
            given(taskRepository.existsByIncidentIdAndEscalationLevel(
                    INCIDENT_ID, 1)).willReturn(false);
            given(taskRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            escalationService.scheduleEscalation(
                    INCIDENT_ID, TENANT_ID, openedAt, "HIGH", "Test");

            // then
            final ArgumentCaptor<EscalationTask> captor =
                    ArgumentCaptor.forClass(EscalationTask.class);
            then(taskRepository).should().save(captor.capture());

            assertThat(captor.getValue().getScheduledEscalationAt())
                    .isEqualTo(openedAt.plusSeconds(15 * 60L));
        }

        @Test
        @DisplayName("should schedule MEDIUM escalation after 30 minutes")
        void shouldScheduleMediumEscalationAfter30Minutes() {
            // given
            final Instant openedAt = Instant.now();
            given(taskRepository.existsByIncidentIdAndEscalationLevel(
                    INCIDENT_ID, 1)).willReturn(false);
            given(taskRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            escalationService.scheduleEscalation(
                    INCIDENT_ID, TENANT_ID, openedAt, "MEDIUM", "Test");

            // then
            final ArgumentCaptor<EscalationTask> captor =
                    ArgumentCaptor.forClass(EscalationTask.class);
            then(taskRepository).should().save(captor.capture());

            assertThat(captor.getValue().getScheduledEscalationAt())
                    .isEqualTo(openedAt.plusSeconds(30 * 60L));
        }

        @Test
        @DisplayName("should skip if level 1 task already exists (idempotency)")
        void shouldSkipIfLevel1TaskAlreadyExists() {
            // given
            given(taskRepository.existsByIncidentIdAndEscalationLevel(
                    INCIDENT_ID, 1)).willReturn(true);

            // when
            escalationService.scheduleEscalation(
                    INCIDENT_ID, TENANT_ID, Instant.now(),
                    "CRITICAL", "High CPU");

            // then
            then(taskRepository).should(never()).save(any());
        }
    }

    @Nested
    @DisplayName("scheduleLevel2Escalation")
    class ScheduleLevel2Escalation {

        @Test
        @DisplayName("should create level 2 escalation task")
        void shouldCreateLevel2Task() {
            // given
            given(taskRepository.existsByIncidentIdAndEscalationLevel(
                    INCIDENT_ID, 2)).willReturn(false);
            given(taskRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            escalationService.scheduleLevel2Escalation(
                    INCIDENT_ID, TENANT_ID, "CRITICAL", "High CPU");

            // then
            final ArgumentCaptor<EscalationTask> captor =
                    ArgumentCaptor.forClass(EscalationTask.class);
            then(taskRepository).should().save(captor.capture());

            assertThat(captor.getValue().getEscalationLevel()).isEqualTo(2);
            assertThat(captor.getValue().getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("should skip if level 2 task already exists (idempotency)")
        void shouldSkipIfLevel2TaskAlreadyExists() {
            // given
            given(taskRepository.existsByIncidentIdAndEscalationLevel(
                    INCIDENT_ID, 2)).willReturn(true);

            // when
            escalationService.scheduleLevel2Escalation(
                    INCIDENT_ID, TENANT_ID, "CRITICAL", "High CPU");

            // then
            then(taskRepository).should(never()).save(any());
        }
    }

    @Nested
    @DisplayName("cancelEscalation")
    class CancelEscalation {

        @Test
        @DisplayName("should cancel all PENDING tasks when ACK received")
        void shouldCancelAllPendingTasks() {
            // given
            final EscalationTask task1 = EscalationTask.createLevel1(
                    INCIDENT_ID, TENANT_ID, Instant.now(),
                    "CRITICAL", "High CPU");
            final EscalationTask task2 = EscalationTask.createLevel2(
                    INCIDENT_ID, TENANT_ID, Instant.now(),
                    "CRITICAL", "High CPU");

            given(taskRepository.findAllByIncidentId(INCIDENT_ID))
                    .willReturn(List.of(task1, task2));
            given(taskRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            escalationService.cancelEscalation(INCIDENT_ID, TENANT_ID);

            // then
            assertThat(task1.getStatus()).isEqualTo("CANCELLED");
            assertThat(task2.getStatus()).isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("should do nothing if no tasks found")
        void shouldDoNothingIfNoTasksFound() {
            // given
            given(taskRepository.findAllByIncidentId(INCIDENT_ID))
                    .willReturn(List.of());

            // when
            escalationService.cancelEscalation(INCIDENT_ID, TENANT_ID);

            // then
            then(taskRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("should not cancel already ESCALATED task")
        void shouldNotCancelEscalatedTask() {
            // given
            final EscalationTask task = EscalationTask.createLevel1(
                    INCIDENT_ID, TENANT_ID, Instant.now(),
                    "CRITICAL", "High CPU");
            task.markEscalated();

            given(taskRepository.findAllByIncidentId(INCIDENT_ID))
                    .willReturn(List.of(task));

            // when
            escalationService.cancelEscalation(INCIDENT_ID, TENANT_ID);

            // then
            assertThat(task.getStatus()).isEqualTo("ESCALATED");
            then(taskRepository).should(never()).save(any());
        }
    }
}