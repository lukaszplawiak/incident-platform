package com.incidentplatform.incident.service;

import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.incident.domain.IncidentHistory;
import com.incidentplatform.incident.domain.IncidentStatus;
import com.incidentplatform.incident.dto.IncidentDto;
import com.incidentplatform.incident.repository.IncidentHistoryRepository;
import com.incidentplatform.incident.repository.IncidentRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * Tests for {@link IncidentCreationService} — previously had no test
 * coverage of its own, since the logic under test here used to be a
 * private method inline in {@link IncidentCommandService} before backlog
 * #74's fix extracted it into this separate, independently-transactional
 * class (see that class's own Javadoc for why the extraction itself was
 * necessary, not just a refactor for its own sake — Spring's
 * {@code REQUIRES_NEW} propagation requires a genuine, separate bean to
 * take effect, since self-invocation bypasses the AOP proxy entirely).
 *
 * <p>{@code tryCreate} deliberately lets
 * {@link DataIntegrityViolationException} propagate uncaught on conflict
 * (rather than catching it internally and returning a sentinel) — see
 * that method's own Javadoc for why: catching it would have required
 * {@code TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()},
 * which throws {@code NoTransactionException} outside a real,
 * Spring-managed transactional context, making the method untestable
 * with a plain Mockito unit test like this one. The tests below reflect
 * that: {@code LostRace} asserts the exception propagates, via
 * {@code assertThatThrownBy}, not that the method returns some empty value.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentCreationService")
class IncidentCreationServiceTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private IncidentHistoryRepository historyRepository;
    @Mock private IncidentEventPublisher eventPublisher;
    @Mock private AuditEventPublisher auditEventPublisher;

    private IncidentCreationService creationService;

    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        creationService = new IncidentCreationService(
                incidentRepository, historyRepository,
                eventPublisher, auditEventPublisher);
    }

    private UnifiedAlertDto buildAlert(UUID teamId) {
        return new UnifiedAlertDto(
                UUID.randomUUID(), TENANT_ID, "prometheus",
                SourceType.OPS, Severity.CRITICAL,
                "High CPU usage on prod-server-1",
                "CPU exceeded 95%",
                Instant.now().minusSeconds(60),
                "prometheus:highcpuusage:server-1",
                Map.of("instance", "server-1:9100"),
                teamId
        );
    }

    @Nested
    @DisplayName("tryCreate — success")
    class Success {

        @Test
        @DisplayName("creates the incident, saves history, publishes event and audit, " +
                "returns the DTO")
        void createsIncidentAndReturnsDto() {
            final UnifiedAlertDto alert = buildAlert(null);
            given(incidentRepository.saveAndFlush(any(Incident.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            final IncidentDto result = creationService.tryCreate(alert, TENANT_ID);

            assertThat(result.status()).isEqualTo(IncidentStatus.OPEN);
            assertThat(result.severity()).isEqualTo(Severity.CRITICAL);
            then(historyRepository).should().save(any(IncidentHistory.class));
            then(eventPublisher).should().publishOpened(any(Incident.class));
            then(auditEventPublisher).should().publishIncident(
                    any(), eq(TENANT_ID), any(), any(), any(), any());
        }

        @Test
        @DisplayName("assigns the team when the alert carries a teamId")
        void assignsTeamWhenPresent() {
            final UUID teamId = UUID.randomUUID();
            final UnifiedAlertDto alert = buildAlert(teamId);

            final ArgumentCaptor<Incident> captor =
                    ArgumentCaptor.forClass(Incident.class);
            given(incidentRepository.saveAndFlush(captor.capture()))
                    .willAnswer(inv -> inv.getArgument(0));

            creationService.tryCreate(alert, TENANT_ID);

            assertThat(captor.getValue().getTeamId()).isEqualTo(teamId);
        }

        @Test
        @DisplayName("does not assign a team when the alert carries none")
        void doesNotAssignTeamWhenAbsent() {
            final UnifiedAlertDto alert = buildAlert(null);

            final ArgumentCaptor<Incident> captor =
                    ArgumentCaptor.forClass(Incident.class);
            given(incidentRepository.saveAndFlush(captor.capture()))
                    .willAnswer(inv -> inv.getArgument(0));

            creationService.tryCreate(alert, TENANT_ID);

            assertThat(captor.getValue().getTeamId()).isNull();
        }
    }

    /**
     * The actual regression coverage for backlog #74 — simulates
     * {@code uq_incidents_active_tenant_fingerprint} (migration V11)
     * rejecting the insert because another replica concurrently created
     * the active incident for this fingerprint first.
     */
    @Nested
    @DisplayName("tryCreate — lost the creation race (backlog #74)")
    class LostRace {

        @Test
        @DisplayName("propagates DataIntegrityViolationException when saveAndFlush throws")
        void propagatesConstraintViolation() {
            final UnifiedAlertDto alert = buildAlert(null);
            given(incidentRepository.saveAndFlush(any(Incident.class)))
                    .willThrow(new DataIntegrityViolationException(
                            "duplicate key value violates unique constraint " +
                                    "\"uq_incidents_active_tenant_fingerprint\""));

            assertThatThrownBy(() -> creationService.tryCreate(alert, TENANT_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("does not save history, publish an event, or publish audit " +
                "when the creation itself failed")
        void doesNotPerformFollowUpWritesOnConstraintViolation() {
            final UnifiedAlertDto alert = buildAlert(null);
            given(incidentRepository.saveAndFlush(any(Incident.class)))
                    .willThrow(new DataIntegrityViolationException("conflict"));

            assertThatThrownBy(() -> creationService.tryCreate(alert, TENANT_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);

            then(historyRepository).should(never()).save(any());
            then(eventPublisher).should(never()).publishOpened(any());
            then(auditEventPublisher).should(never())
                    .publishIncident(any(), any(), any(), any(), any(), any());
        }
    }
}