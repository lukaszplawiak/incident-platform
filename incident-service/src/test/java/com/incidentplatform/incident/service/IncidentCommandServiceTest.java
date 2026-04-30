package com.incidentplatform.incident.service;

import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.incident.domain.IncidentHistory;
import com.incidentplatform.incident.domain.IncidentStatus;
import com.incidentplatform.incident.dto.IncidentDto;
import com.incidentplatform.incident.dto.UpdateStatusCommand;
import com.incidentplatform.incident.repository.IncidentHistoryRepository;
import com.incidentplatform.incident.repository.IncidentRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;
import com.incidentplatform.shared.events.SourceType;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentCommandService")
class IncidentCommandServiceTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private IncidentHistoryRepository historyRepository;
    @Mock private IncidentEventPublisher eventPublisher;
    @Mock private IncidentWebSocketPublisher webSocketPublisher;
    @Mock private AuditEventPublisher auditEventPublisher;

    private IncidentCommandService commandService;

    private static final String TENANT_ID = "test-tenant";
    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        commandService = new IncidentCommandService(
                incidentRepository, historyRepository,
                eventPublisher, webSocketPublisher, auditEventPublisher);
    }

    @Nested
    @DisplayName("createFromAlert")
    class CreateFromAlert {

        @Test
        @DisplayName("should create new incident when no duplicate exists")
        void shouldCreateNewIncidentWhenNoDuplicate() {
            // given
            final UnifiedAlertDto alert = buildAlert(Severity.CRITICAL,
                    "prometheus:highcpuusage:server-1");

            given(incidentRepository.existsActiveByTenantIdAndAlertFingerprint(
                    TENANT_ID, alert.fingerprint())).willReturn(false);
            given(incidentRepository.save(any(Incident.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(historyRepository.save(any(IncidentHistory.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // when
            commandService.createFromAlert(alert, TENANT_ID);

            // then
            then(incidentRepository).should(times(1)).save(any(Incident.class));
            then(historyRepository).should(times(1)).save(any(IncidentHistory.class));
            then(eventPublisher).should(times(1)).publishOpened(any(Incident.class));
            then(webSocketPublisher).should(times(1)).publishCreated(any(IncidentDto.class));
        }

        @Test
        @DisplayName("should create new incident with correct severity")
        void shouldCreateNewIncidentWithCorrectSeverity() {
            // given
            final UnifiedAlertDto alert = buildAlert(Severity.HIGH,
                    "prometheus:alert:server-1");

            given(incidentRepository.existsActiveByTenantIdAndAlertFingerprint(
                    anyString(), anyString())).willReturn(false);
            given(incidentRepository.save(any(Incident.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(historyRepository.save(any(IncidentHistory.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // when
            commandService.createFromAlert(alert, TENANT_ID);

            // then
            final ArgumentCaptor<Incident> captor =
                    ArgumentCaptor.forClass(Incident.class);
            then(incidentRepository).should().save(captor.capture());

            final Incident saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(IncidentStatus.OPEN);
            assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(saved.getSeverity()).isEqualTo(Severity.HIGH);
        }

        @Test
        @DisplayName("should ignore duplicate alert with same or lower severity")
        void shouldIgnoreDuplicateWithSameSeverity() {
            // given
            final UnifiedAlertDto alert = buildAlert(Severity.HIGH,
                    "prometheus:highcpuusage:server-1");
            final Incident existingIncident = buildIncident(Severity.HIGH,
                    "prometheus:highcpuusage:server-1");

            given(incidentRepository.existsActiveByTenantIdAndAlertFingerprint(
                    TENANT_ID, alert.fingerprint())).willReturn(true);
            given(incidentRepository.findActiveByAlertFingerprintAndTenantId(
                    alert.fingerprint(), TENANT_ID))
                    .willReturn(Optional.of(existingIncident));

            // when
            commandService.createFromAlert(alert, TENANT_ID);

            // then
            then(incidentRepository).should(never()).save(any(Incident.class));
            then(eventPublisher).should(never()).publishOpened(any(Incident.class));
        }

        @Test
        @DisplayName("should update severity when duplicate has lower severity")
        void shouldUpdateSeverityWhenDuplicateHasLowerSeverity() {
            // given
            final UnifiedAlertDto alert = buildAlert(Severity.CRITICAL,
                    "prometheus:highcpuusage:server-1");
            final Incident existingIncident = buildIncident(Severity.LOW,
                    "prometheus:highcpuusage:server-1");

            given(incidentRepository.existsActiveByTenantIdAndAlertFingerprint(
                    TENANT_ID, alert.fingerprint())).willReturn(true);
            given(incidentRepository.findActiveByAlertFingerprintAndTenantId(
                    alert.fingerprint(), TENANT_ID))
                    .willReturn(Optional.of(existingIncident));
            given(incidentRepository.save(any(Incident.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(historyRepository.save(any(IncidentHistory.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // when
            commandService.createFromAlert(alert, TENANT_ID);

            // then
            final ArgumentCaptor<Incident> incidentCaptor =
                    ArgumentCaptor.forClass(Incident.class);
            then(incidentRepository).should().save(incidentCaptor.capture());
            assertThat(incidentCaptor.getValue().getSeverity())
                    .isEqualTo(Severity.CRITICAL);
        }

        @Test
        @DisplayName("should NOT update severity when duplicate has higher severity")
        void shouldNotUpdateSeverityWhenDuplicateHasHigherSeverity() {
            // given
            final UnifiedAlertDto alert = buildAlert(Severity.LOW,
                    "prometheus:highcpuusage:server-1");
            final Incident existingIncident = buildIncident(Severity.CRITICAL,
                    "prometheus:highcpuusage:server-1");

            given(incidentRepository.existsActiveByTenantIdAndAlertFingerprint(
                    TENANT_ID, alert.fingerprint())).willReturn(true);
            given(incidentRepository.findActiveByAlertFingerprintAndTenantId(
                    alert.fingerprint(), TENANT_ID))
                    .willReturn(Optional.of(existingIncident));

            // when
            commandService.createFromAlert(alert, TENANT_ID);

            // then
            then(incidentRepository).should(never()).save(any(Incident.class));
        }
    }

    @Nested
    @DisplayName("autoResolve")
    class AutoResolve {

        @Test
        @DisplayName("should resolve incident when active incident found")
        void shouldResolveIncidentWhenFound() {
            // given
            final String fingerprint = "prometheus:highcpuusage:server-1";
            final Incident incident = buildIncidentWithStatus(
                    fingerprint, IncidentStatus.ACKNOWLEDGED);
            final ResolvedAlertNotification notification =
                    buildResolvedNotification(fingerprint);

            given(incidentRepository.findActiveByAlertFingerprintAndTenantId(
                    fingerprint, TENANT_ID))
                    .willReturn(Optional.of(incident));
            given(incidentRepository.save(any(Incident.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(historyRepository.save(any(IncidentHistory.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // when
            commandService.autoResolve(notification, TENANT_ID);

            // then
            assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
            then(incidentRepository).should().save(incident);
            then(eventPublisher).should().publishResolved(any(Incident.class), any());
            then(webSocketPublisher).should()
                    .publishStatusChanged(any(IncidentDto.class), anyString());
        }

        @Test
        @DisplayName("should do nothing when no active incident found")
        void shouldDoNothingWhenNoIncidentFound() {
            // given
            final String fingerprint = "prometheus:unknown:server-1";
            final ResolvedAlertNotification notification =
                    buildResolvedNotification(fingerprint);

            given(incidentRepository.findActiveByAlertFingerprintAndTenantId(
                    fingerprint, TENANT_ID))
                    .willReturn(Optional.empty());

            // when
            commandService.autoResolve(notification, TENANT_ID);

            // then
            then(incidentRepository).should(never()).save(any(Incident.class));
            then(eventPublisher).should(never())
                    .publishResolved(any(Incident.class), any());
        }

        @Test
        @DisplayName("should not resolve already resolved incident")
        void shouldNotResolveAlreadyResolvedIncident() {
            // given
            final String fingerprint = "prometheus:alert:server-1";
            final Incident resolvedIncident = buildIncidentWithStatus(
                    fingerprint, IncidentStatus.ACKNOWLEDGED);
            resolvedIncident.transitionTo(IncidentStatus.RESOLVED);

            final ResolvedAlertNotification notification =
                    buildResolvedNotification(fingerprint);

            given(incidentRepository.findActiveByAlertFingerprintAndTenantId(
                    fingerprint, TENANT_ID))
                    .willReturn(Optional.of(resolvedIncident));

            // when
            commandService.autoResolve(notification, TENANT_ID);

            // then
            then(incidentRepository).should(never()).save(any(Incident.class));
        }
    }

    @Nested
    @DisplayName("updateStatus")
    class UpdateStatus {

        @Test
        @DisplayName("should update status via FSM")
        void shouldUpdateStatusViaFsm() {
            // given
            final Incident incident = buildIncident(Severity.HIGH,
                    "prometheus:alert:server-1");
            final UpdateStatusCommand command = new UpdateStatusCommand(
                    IncidentStatus.ACKNOWLEDGED, "I am on it");

            given(incidentRepository.findByIdAndTenantId(incident.getId(), TENANT_ID))
                    .willReturn(Optional.of(incident));
            given(incidentRepository.save(any(Incident.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(historyRepository.save(any(IncidentHistory.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // when
            final IncidentDto result = commandService.updateStatus(
                    incident.getId(), command, USER_ID, TENANT_ID);

            // then
            assertThat(result.status()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
            then(incidentRepository).should().save(any(Incident.class));
            then(eventPublisher).should()
                    .publishAcknowledged(any(Incident.class), any(UUID.class));
            then(webSocketPublisher).should()
                    .publishStatusChanged(any(IncidentDto.class), anyString());
        }

        @Test
        @DisplayName("should assign incident to user when acknowledging")
        void shouldAssignWhenAcknowledging() {
            // given
            final Incident incident = buildIncident(Severity.HIGH,
                    "prometheus:alert:server-1");
            final UpdateStatusCommand command = new UpdateStatusCommand(
                    IncidentStatus.ACKNOWLEDGED, null);

            given(incidentRepository.findByIdAndTenantId(incident.getId(), TENANT_ID))
                    .willReturn(Optional.of(incident));
            given(incidentRepository.save(any(Incident.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(historyRepository.save(any(IncidentHistory.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // when
            commandService.updateStatus(incident.getId(), command, USER_ID, TENANT_ID);

            // then
            assertThat(incident.getAssignedTo()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when incident not found")
        void shouldThrowWhenIncidentNotFound() {
            // given
            final UUID unknownId = UUID.randomUUID();
            final UpdateStatusCommand command = new UpdateStatusCommand(
                    IncidentStatus.ACKNOWLEDGED, null);

            given(incidentRepository.findByIdAndTenantId(unknownId, TENANT_ID))
                    .willReturn(Optional.empty());

            // then
            assertThatThrownBy(() -> commandService.updateStatus(
                    unknownId, command, USER_ID, TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining(unknownId.toString());
        }

        @Test
        @DisplayName("should throw BusinessException for invalid FSM transition")
        void shouldThrowForInvalidTransition() {
            // given
            final Incident incident = buildIncident(Severity.HIGH,
                    "prometheus:alert:server-1");
            final UpdateStatusCommand command = new UpdateStatusCommand(
                    IncidentStatus.CLOSED, null);

            given(incidentRepository.findByIdAndTenantId(incident.getId(), TENANT_ID))
                    .willReturn(Optional.of(incident));

            // then
            assertThatThrownBy(() -> commandService.updateStatus(
                    incident.getId(), command, USER_ID, TENANT_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("OPEN")
                    .hasMessageContaining("CLOSED");
        }

        @Test
        @DisplayName("should save history entry on status change")
        void shouldSaveHistoryOnStatusChange() {
            // given
            final Incident incident = buildIncident(Severity.CRITICAL,
                    "prometheus:alert:server-1");
            final UpdateStatusCommand command = new UpdateStatusCommand(
                    IncidentStatus.ACKNOWLEDGED, "Investigating now");

            given(incidentRepository.findByIdAndTenantId(incident.getId(), TENANT_ID))
                    .willReturn(Optional.of(incident));
            given(incidentRepository.save(any(Incident.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(historyRepository.save(any(IncidentHistory.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // when
            commandService.updateStatus(incident.getId(), command, USER_ID, TENANT_ID);

            // then
            final ArgumentCaptor<IncidentHistory> historyCaptor =
                    ArgumentCaptor.forClass(IncidentHistory.class);
            then(historyRepository).should().save(historyCaptor.capture());

            final IncidentHistory history = historyCaptor.getValue();
            assertThat(history.getFromStatus()).isEqualTo(IncidentStatus.OPEN);
            assertThat(history.getToStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
            assertThat(history.getChangedBy()).isEqualTo(USER_ID);
            assertThat(history.getChangeSource()).isEqualTo("REST_API");
            assertThat(history.getComment()).isEqualTo("Investigating now");
        }
    }

    @Nested
    @DisplayName("assignTo")
    class AssignTo {

        @Test
        @DisplayName("should assign incident to user")
        void shouldAssignIncidentToUser() {
            // given
            final Incident incident = buildIncident(Severity.HIGH,
                    "prometheus:alert:server-1");
            final UUID assignToId = UUID.randomUUID();

            given(incidentRepository.findByIdAndTenantId(incident.getId(), TENANT_ID))
                    .willReturn(Optional.of(incident));
            given(incidentRepository.save(any(Incident.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // when
            final IncidentDto result = commandService.assignTo(
                    incident.getId(), assignToId, TENANT_ID);

            // then
            assertThat(result.assignedTo()).isEqualTo(assignToId);
            then(webSocketPublisher).should().publishUpdate(any(IncidentDto.class));
        }

        @Test
        @DisplayName("should throw ResourceNotFoundException when incident not found")
        void shouldThrowWhenIncidentNotFound() {
            // given
            final UUID unknownId = UUID.randomUUID();
            given(incidentRepository.findByIdAndTenantId(unknownId, TENANT_ID))
                    .willReturn(Optional.empty());

            // then
            assertThatThrownBy(() ->
                    commandService.assignTo(unknownId, USER_ID, TENANT_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    private UnifiedAlertDto buildAlert(Severity severity, String fingerprint) {
        return new UnifiedAlertDto(
                UUID.randomUUID(), TENANT_ID, "prometheus",
                SourceType.OPS, severity,
                "High CPU usage on prod-server-1",
                "CPU exceeded 95%",
                Instant.now().minusSeconds(60),
                fingerprint,
                Map.of("instance", "server-1:9100")
        );
    }

    private Incident buildIncident(Severity severity, String fingerprint) {
        return new Incident(
                TENANT_ID,
                "High CPU usage on prod-server-1",
                "CPU exceeded 95%",
                severity,
                SourceType.OPS,
                "prometheus",
                fingerprint,
                UUID.randomUUID(),
                Instant.now().minusSeconds(60)
        );
    }

    private Incident buildIncidentWithStatus(String fingerprint,
                                             IncidentStatus status) {
        final Incident incident = buildIncident(Severity.HIGH, fingerprint);
        if (status != IncidentStatus.OPEN) {
            incident.transitionTo(status);
        }
        return incident;
    }

    private ResolvedAlertNotification buildResolvedNotification(String fingerprint) {
        return ResolvedAlertNotification.of(
                TENANT_ID, "prometheus", fingerprint, Instant.now());
    }
}