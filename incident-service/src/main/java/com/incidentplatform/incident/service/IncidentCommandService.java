package com.incidentplatform.incident.service;

import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.incident.domain.IncidentFsm;
import com.incidentplatform.incident.domain.IncidentHistory;
import com.incidentplatform.incident.domain.IncidentStatus;
import com.incidentplatform.incident.dto.IncidentDto;
import com.incidentplatform.incident.dto.UpdateStatusCommand;
import com.incidentplatform.incident.repository.IncidentHistoryRepository;
import com.incidentplatform.incident.repository.IncidentRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class IncidentCommandService {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentCommandService.class);

    private static final String SERVICE_NAME = "incident-service";

    private final IncidentRepository incidentRepository;
    private final IncidentHistoryRepository historyRepository;
    private final IncidentEventPublisher eventPublisher;
    private final IncidentWebSocketPublisher webSocketPublisher;
    private final AuditEventPublisher auditEventPublisher;

    public IncidentCommandService(
            IncidentRepository incidentRepository,
            IncidentHistoryRepository historyRepository,
            IncidentEventPublisher eventPublisher,
            IncidentWebSocketPublisher webSocketPublisher,
            AuditEventPublisher auditEventPublisher) {
        this.incidentRepository = incidentRepository;
        this.historyRepository = historyRepository;
        this.eventPublisher = eventPublisher;
        this.webSocketPublisher = webSocketPublisher;
        this.auditEventPublisher = auditEventPublisher;
    }

    @Transactional
    public void createFromAlert(UnifiedAlertDto alert, String tenantId) {
        log.info("Processing alert: alertId={}, fingerprint={}, severity={}, tenant={}",
                alert.alertId(), alert.fingerprint(), alert.severity(), tenantId);

        final boolean duplicateExists =
                incidentRepository.existsActiveByTenantIdAndAlertFingerprint(
                        tenantId, alert.fingerprint());

        final IncidentDto result = duplicateExists
                ? handleDuplicateAlert(alert, tenantId)
                : createNewIncident(alert, tenantId);

        webSocketPublisher.publishCreated(result);
    }

    @Transactional
    public void autoResolve(ResolvedAlertNotification notification,
                            String tenantId) {
        log.info("Processing auto-resolve: fingerprint={}, tenant={}",
                notification.alertFingerprint(), tenantId);

        final var incidentOpt = incidentRepository
                .findActiveByAlertFingerprintAndTenantId(
                        notification.alertFingerprint(), tenantId);

        if (incidentOpt.isEmpty()) {
            log.info("No active incident for auto-resolve: fingerprint={}, tenant={}",
                    notification.alertFingerprint(), tenantId);
            return;
        }

        final Incident incident = incidentOpt.get();

        if (!IncidentFsm.isTransitionAllowed(
                incident.getStatus(), IncidentStatus.RESOLVED)) {
            log.warn("Cannot auto-resolve incident in status {}: incidentId={}, tenant={}",
                    incident.getStatus(), incident.getId(), tenantId);
            return;
        }

        final IncidentStatus previousStatus = incident.getStatus();
        incident.transitionTo(IncidentStatus.RESOLVED);
        incidentRepository.save(incident);

        historyRepository.save(IncidentHistory.forAutomaticChange(
                incident.getId(), tenantId,
                previousStatus, IncidentStatus.RESOLVED,
                "AUTO_RESOLVE",
                String.format("Auto-resolved by source '%s'", notification.source())
        ));

        log.info("Incident auto-resolved: incidentId={}, {} → RESOLVED, tenant={}",
                incident.getId(), previousStatus, tenantId);

        eventPublisher.publishResolved(incident, null);
        webSocketPublisher.publishStatusChanged(
                IncidentDto.from(incident), previousStatus.name());

        auditEventPublisher.publishSystem(
                incident.getId(), tenantId,
                "INCIDENT_RESOLVED", SERVICE_NAME,
                String.format("Auto-resolved by source '%s' after alert cleared",
                        notification.source()),
                Map.of("source", notification.source(),
                        "previousStatus", previousStatus.name())
        );
    }

    @Transactional
    public IncidentDto updateStatus(UUID incidentId,
                                    UpdateStatusCommand command,
                                    UUID changedBy,
                                    String tenantId) {
        log.info("Updating status: incidentId={}, target={}, changedBy={}, tenant={}",
                incidentId, command.status(), changedBy, tenantId);

        final Incident incident = incidentRepository
                .findByIdAndTenantId(incidentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Incident", incidentId));

        final IncidentStatus previousStatus = incident.getStatus();

        incident.transitionTo(command.status());

        if (command.status() == IncidentStatus.ACKNOWLEDGED
                && incident.getAssignedTo() == null) {
            incident.assignTo(changedBy);
        }

        incidentRepository.save(incident);

        historyRepository.save(new IncidentHistory(
                incident.getId(), tenantId,
                previousStatus, command.status(),
                changedBy, "REST_API",
                command.comment()
        ));

        log.info("Status updated: incidentId={}, {} → {}, tenant={}",
                incidentId, previousStatus, command.status(), tenantId);

        final IncidentDto dto = IncidentDto.from(incident);

        publishStatusChangeEvent(incident, command.status(), changedBy);
        webSocketPublisher.publishStatusChanged(dto, previousStatus.name());

        final String eventType = resolveAuditEventType(command.status());
        auditEventPublisher.publishUser(
                incidentId, tenantId,
                eventType, SERVICE_NAME,
                changedBy.toString(),
                String.format("Status changed: %s → %s. %s",
                        previousStatus, command.status(),
                        command.comment() != null ? command.comment() : ""),
                Map.of("previousStatus", previousStatus.name(),
                        "newStatus", command.status().name())
        );

        return dto;
    }

    @Transactional
    public IncidentDto assignTo(UUID incidentId, UUID assignToId,
                                String tenantId) {
        final Incident incident = incidentRepository
                .findByIdAndTenantId(incidentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Incident", incidentId));

        incident.assignTo(assignToId);
        incidentRepository.save(incident);

        log.info("Incident assigned: incidentId={}, assignedTo={}, tenant={}",
                incidentId, assignToId, tenantId);

        final IncidentDto dto = IncidentDto.from(incident);
        webSocketPublisher.publishUpdate(dto);

        auditEventPublisher.publishUser(
                incidentId, tenantId,
                "INCIDENT_ASSIGNED", SERVICE_NAME,
                assignToId.toString(),
                String.format("Incident assigned to userId=%s", assignToId),
                Map.of("assignedTo", assignToId.toString())
        );

        return dto;
    }

    private IncidentDto createNewIncident(UnifiedAlertDto alert,
                                          String tenantId) {
        final Incident incident = new Incident(
                tenantId,
                alert.title(),
                alert.description(),
                alert.severity(),
                alert.sourceType(),
                alert.source(),
                alert.fingerprint(),
                alert.alertId(),
                alert.firedAt()
        );

        incidentRepository.save(incident);

        historyRepository.save(IncidentHistory.forCreation(
                incident.getId(), tenantId, "KAFKA_CONSUMER"));

        log.info("New incident created: incidentId={}, title='{}', " +
                        "severity={}, tenant={}",
                incident.getId(), incident.getTitle(),
                incident.getSeverity(), tenantId);

        eventPublisher.publishOpened(incident);

        auditEventPublisher.publishSystem(
                incident.getId(), tenantId,
                "INCIDENT_CREATED", SERVICE_NAME,
                String.format("Incident created from %s alert: '%s'",
                        alert.source(), alert.title()),
                Map.of("source", alert.source(),
                        "severity", alert.severity(),
                        "fingerprint", alert.fingerprint())
        );

        return IncidentDto.from(incident);
    }

    private IncidentDto handleDuplicateAlert(UnifiedAlertDto alert,
                                             String tenantId) {
        final var existingOpt = incidentRepository
                .findActiveByAlertFingerprintAndTenantId(
                        alert.fingerprint(), tenantId);

        if (existingOpt.isEmpty()) {
            log.warn("Race condition in dedup: fingerprint={}, tenant={}",
                    alert.fingerprint(), tenantId);
            return createNewIncident(alert, tenantId);
        }

        final Incident existing = existingOpt.get();

        if (isSeverityHigher(alert.severity(), existing.getSeverity())) {
            final String previousSeverity = existing.getSeverity();
            existing.updateSeverity(alert.severity());
            incidentRepository.save(existing);

            historyRepository.save(IncidentHistory.forAutomaticChange(
                    existing.getId(), tenantId,
                    existing.getStatus(), existing.getStatus(),
                    "KAFKA_CONSUMER",
                    String.format("Severity escalated: %s → %s",
                            previousSeverity, alert.severity())
            ));

            log.info("Severity updated: incidentId={}, {} → {}, tenant={}",
                    existing.getId(), previousSeverity,
                    alert.severity(), tenantId);

            auditEventPublisher.publishSystem(
                    existing.getId(), tenantId,
                    "INCIDENT_SEVERITY_UPDATED", SERVICE_NAME,
                    String.format("Severity updated: %s → %s",
                            previousSeverity, alert.severity()),
                    Map.of("previousSeverity", previousSeverity,
                            "newSeverity", alert.severity())
            );
        }

        return IncidentDto.from(existing);
    }

    private String resolveAuditEventType(IncidentStatus status) {
        return switch (status) {
            case ACKNOWLEDGED -> "INCIDENT_ACKNOWLEDGED";
            case RESOLVED     -> "INCIDENT_RESOLVED";
            case CLOSED       -> "INCIDENT_CLOSED";
            case ESCALATED    -> "INCIDENT_ESCALATED";
            default           -> "INCIDENT_STATUS_CHANGED";
        };
    }

    private boolean isSeverityHigher(String newSeverity,
                                     String existingSeverity) {
        return severityWeight(newSeverity) > severityWeight(existingSeverity);
    }

    private int severityWeight(String severity) {
        return switch (severity.toUpperCase()) {
            case "CRITICAL" -> 4;
            case "HIGH"     -> 3;
            case "MEDIUM"   -> 2;
            case "LOW"      -> 1;
            default         -> 0;
        };
    }

    private void publishStatusChangeEvent(Incident incident,
                                          IncidentStatus newStatus,
                                          UUID changedBy) {
        switch (newStatus) {
            case ACKNOWLEDGED ->
                    eventPublisher.publishAcknowledged(incident, changedBy);
            case RESOLVED     ->
                    eventPublisher.publishResolved(incident, changedBy);
            case CLOSED       ->
                    eventPublisher.publishClosed(incident, changedBy, null);
            case ESCALATED    ->
                    eventPublisher.publishEscalated(incident, changedBy, 1);
            default -> log.debug("No event for status: {}", newStatus);
        }
    }
}