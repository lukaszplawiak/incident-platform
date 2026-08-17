package com.incidentplatform.escalation.service;

import com.incidentplatform.escalation.domain.EscalationTask;
import com.incidentplatform.escalation.domain.EscalationTaskStatus;
import com.incidentplatform.escalation.repository.EscalationTaskRepository;
import com.incidentplatform.shared.domain.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

/**
 * Backlog #39. This class did not exist before — extracted from
 * {@code EscalationScheduler.escalate()}'s previously-inline
 * {@code taskRepository.saveAndFlush(task)} call, specifically so that
 * write could happen in its own short transaction, independent of
 * whatever else the scheduler is doing for other tasks in the same poll
 * cycle. See this class's own Javadoc for the full account.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EscalationTaskPersistenceService")
class EscalationTaskPersistenceServiceTest {

    @Mock
    private EscalationTaskRepository taskRepository;

    private EscalationTaskPersistenceService persistenceService;

    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        persistenceService = new EscalationTaskPersistenceService(taskRepository);
    }

    private EscalationTask buildTask() {
        return EscalationTask.createLevel1(
                UUID.randomUUID(), TENANT_ID, UUID.randomUUID(),
                Instant.now(), Severity.CRITICAL, "High CPU Usage");
    }

    @Test
    @DisplayName("marks the task ESCALATED and persists via saveAndFlush")
    void marksEscalatedAndPersists() {
        // given
        final EscalationTask task = buildTask();

        // when
        persistenceService.markEscalated(task);

        // then
        assertThat(task.getStatus()).isEqualTo(EscalationTaskStatus.ESCALATED);

        final ArgumentCaptor<EscalationTask> captor =
                ArgumentCaptor.forClass(EscalationTask.class);
        then(taskRepository).should().saveAndFlush(captor.capture());
        assertThat(captor.getValue()).isSameAs(task);
    }

    /**
     * Regression test confirming this class deliberately does NOT catch
     * OptimisticLockingFailureException — see this class's own Javadoc.
     * EscalationScheduler.escalate() depends on this exception reaching
     * it uncaught, to distinguish "concurrently cancelled, skip
     * gracefully" (backlog #38) from a genuine failure.
     */
    @Test
    @DisplayName("propagates OptimisticLockingFailureException rather than catching it")
    void propagatesOptimisticLockConflict() {
        // given
        final EscalationTask task = buildTask();
        willThrow(new OptimisticLockingFailureException(
                "Row was updated or deleted by another transaction"))
                .given(taskRepository).saveAndFlush(any());

        // when / then
        assertThatThrownBy(() -> persistenceService.markEscalated(task))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    /**
     * The actual regression coverage for backlog #41. Verifies the entity
     * mutation (retryCount incremented, errorMessage set, status
     * unchanged — still PENDING, so the next poll cycle retries it) and
     * that it's persisted via saveAndFlush, matching markEscalated's own
     * pattern.
     */
    @Test
    @DisplayName("records a failed attempt — increments retryCount, sets " +
            "errorMessage, leaves status PENDING for retry")
    void recordsFailedAttemptAndPersists() {
        // given
        final EscalationTask task = buildTask();

        // when
        persistenceService.recordFailedAttempt(task, "oncall-service unreachable");

        // then
        assertThat(task.getRetryCount()).isEqualTo(1);
        assertThat(task.getErrorMessage()).isEqualTo("oncall-service unreachable");
        assertThat(task.getStatus()).isEqualTo(EscalationTaskStatus.PENDING);

        final ArgumentCaptor<EscalationTask> captor =
                ArgumentCaptor.forClass(EscalationTask.class);
        then(taskRepository).should().saveAndFlush(captor.capture());
        assertThat(captor.getValue()).isSameAs(task);
    }

    @Test
    @DisplayName("increments retryCount cumulatively across repeated failed attempts")
    void incrementsRetryCountCumulatively() {
        // given
        final EscalationTask task = buildTask();

        // when
        persistenceService.recordFailedAttempt(task, "first failure");
        persistenceService.recordFailedAttempt(task, "second failure");
        persistenceService.recordFailedAttempt(task, "third failure");

        // then
        assertThat(task.getRetryCount()).isEqualTo(3);
        assertThat(task.getErrorMessage()).isEqualTo("third failure");
    }

    @Test
    @DisplayName("recordFailedAttempt also propagates OptimisticLockingFailureException " +
            "rather than catching it — same contract as markEscalated")
    void recordFailedAttemptPropagatesOptimisticLockConflict() {
        // given
        final EscalationTask task = buildTask();
        willThrow(new OptimisticLockingFailureException(
                "Row was updated or deleted by another transaction"))
                .given(taskRepository).saveAndFlush(any());

        // when / then
        assertThatThrownBy(() ->
                persistenceService.recordFailedAttempt(task, "some error"))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}