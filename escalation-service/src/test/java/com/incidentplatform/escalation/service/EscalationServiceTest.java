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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
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
        ReflectionTestUtils.setField(escalationService,
                "thresholdMinutes", 15);
    }

    @Nested
    @DisplayName("scheduleEscalation")
    class ScheduleEscalation {

        @Test
        @DisplayName("should create escalation task for new incident")
        void shouldCreateTaskForNewIncident() {
            // given
            given(taskRepository.existsByIncidentId(INCIDENT_ID))
                    .willReturn(false);
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
            assertThat(saved.getTitle()).isEqualTo("High CPU Usage");
        }

        @Test
        @DisplayName("should schedule escalation 15 minutes after opening")
        void shouldScheduleEscalationAfterThreshold() {
            // given
            final Instant openedAt = Instant.now();
            given(taskRepository.existsByIncidentId(INCIDENT_ID))
                    .willReturn(false);
            given(taskRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            escalationService.scheduleEscalation(
                    INCIDENT_ID, TENANT_ID, openedAt, "HIGH", "Test");

            // then
            final ArgumentCaptor<EscalationTask> captor =
                    ArgumentCaptor.forClass(EscalationTask.class);
            then(taskRepository).should().save(captor.capture());

            final Instant expectedEscalationAt =
                    openedAt.plusSeconds(15 * 60L);
            assertThat(captor.getValue().getScheduledEscalationAt())
                    .isEqualTo(expectedEscalationAt);
        }

        @Test
        @DisplayName("should skip if escalation task already exists (idempotency)")
        void shouldSkipIfTaskAlreadyExists() {
            // given
            given(taskRepository.existsByIncidentId(INCIDENT_ID))
                    .willReturn(true);

            // when
            escalationService.scheduleEscalation(
                    INCIDENT_ID, TENANT_ID, Instant.now(),
                    "CRITICAL", "High CPU");

            // then
            then(taskRepository).should(never()).save(any());
        }
    }

    @Nested
    @DisplayName("cancelEscalation")
    class CancelEscalation {

        @Test
        @DisplayName("should cancel PENDING task when ACK received")
        void shouldCancelPendingTask() {
            // given
            final EscalationTask task = EscalationTask.create(
                    INCIDENT_ID, TENANT_ID, Instant.now(), 15,
                    "CRITICAL", "High CPU");

            given(taskRepository.findByIncidentId(INCIDENT_ID))
                    .willReturn(Optional.of(task));
            given(taskRepository.save(any())).willAnswer(i -> i.getArgument(0));

            // when
            escalationService.cancelEscalation(INCIDENT_ID, TENANT_ID);

            // then
            assertThat(task.getStatus()).isEqualTo("CANCELLED");
            then(taskRepository).should().save(task);
        }

        @Test
        @DisplayName("should do nothing if no task found")
        void shouldDoNothingIfNoTaskFound() {
            // given
            given(taskRepository.findByIncidentId(INCIDENT_ID))
                    .willReturn(Optional.empty());

            // when
            escalationService.cancelEscalation(INCIDENT_ID, TENANT_ID);

            // then
            then(taskRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("should not cancel already ESCALATED task")
        void shouldNotCancelEscalatedTask() {
            // given
            final EscalationTask task = EscalationTask.create(
                    INCIDENT_ID, TENANT_ID, Instant.now(), 15,
                    "CRITICAL", "High CPU");
            task.markEscalated();

            given(taskRepository.findByIncidentId(INCIDENT_ID))
                    .willReturn(Optional.of(task));

            // when
            escalationService.cancelEscalation(INCIDENT_ID, TENANT_ID);

            // then
            assertThat(task.getStatus()).isEqualTo("ESCALATED");
            then(taskRepository).should(never()).save(any());
        }
    }
}