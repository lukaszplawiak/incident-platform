package com.incidentplatform.incident.service;

import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.incident.domain.IncidentFsm;
import com.incidentplatform.incident.domain.IncidentHistory;
import com.incidentplatform.incident.domain.IncidentStatus;
import com.incidentplatform.incident.dto.IncidentDto;
import com.incidentplatform.incident.dto.AssignTeamRequest;
import com.incidentplatform.incident.dto.UpdateStatusCommand;
import com.incidentplatform.incident.repository.IncidentHistoryRepository;
import com.incidentplatform.incident.repository.IncidentRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.audit.ChangeSource;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import com.incidentplatform.shared.events.ResolvedAlertNotification;
import com.incidentplatform.shared.exception.BusinessException;
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

        if (duplicateExists) {
            // An existing incident with this fingerprint is still active —
            // this is never a "new incident" from the client's point of view.
            // handleDuplicateAlert() reports whether anything actually changed
            // (severity escalation) so we only publish a WebSocket event when
            // there is something for the UI to refresh — repeated re-fires of
            // the same alert with unchanged severity produce no event at all,
            // rather than a misleading INCIDENT_CREATED for an incident the
            // dashboard already has.
            final DuplicateAlertResult result = handleDuplicateAlert(alert, tenantId);
            if (result.severityChanged()) {
                webSocketPublisher.publishUpdate(result.dto());
            }
        } else {
            webSocketPublisher.publishCreated(createNewIncident(alert, tenantId));
        }
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
        incident.resolve();
        incidentRepository.save(incident);

        historyRepository.save(IncidentHistory.forAutomaticChange(
                incident.getId(), tenantId,
                previousStatus, IncidentStatus.RESOLVED,
                ChangeSource.AUTO_RESOLVE,
                String.format("Auto-resolved by source '%s'", notification.source())
        ));

        log.info("Incident auto-resolved: incidentId={}, {} → RESOLVED, tenant={}",
                incident.getId(), previousStatus, tenantId);

        eventPublisher.publishResolved(incident, null);
        webSocketPublisher.publishStatusChanged(
                IncidentDto.from(incident), previousStatus.name());

        auditEventPublisher.publishIncident(
                incident.getId(), tenantId,
                AuditEventTypes.INCIDENT_RESOLVED, SERVICE_NAME,
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

        applyTransition(incident, command.status(), changedBy);

        incidentRepository.save(incident);

        historyRepository.save(new IncidentHistory(
                incident.getId(), tenantId,
                previousStatus, command.status(),
                changedBy, ChangeSource.REST_API,
                command.comment()
        ));

        log.info("Status updated: incidentId={}, {} → {}, tenant={}",
                incidentId, previousStatus, command.status(), tenantId);

        final IncidentDto dto = IncidentDto.from(incident);

        publishStatusChangeEvent(incident, command.status(), changedBy);
        webSocketPublisher.publishStatusChanged(dto, previousStatus.name());

        auditEventPublisher.publishIncidentUser(
                incidentId, tenantId,
                command.status().auditEventType(), SERVICE_NAME,
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
                                UUID assignedBy, String tenantId) {
        final Incident incident = incidentRepository
                .findByIdAndTenantId(incidentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Incident", incidentId));

        incident.assignTo(assignToId);
        incidentRepository.save(incident);

        log.info("Incident assigned: incidentId={}, assignedTo={}, assignedBy={}, tenant={}",
                incidentId, assignToId, assignedBy, tenantId);

        final IncidentDto dto = IncidentDto.from(incident);
        webSocketPublisher.publishUpdate(dto);

        auditEventPublisher.publishIncidentUser(
                incidentId, tenantId,
                AuditEventTypes.INCIDENT_ASSIGNED, SERVICE_NAME,
                assignedBy.toString(),
                String.format("Incident assigned to userId=%s", assignToId),
                Map.of("assignedTo", assignToId.toString(),
                        "assignedBy", assignedBy.toString())
        );

        return dto;
    }


    @Transactional
    public IncidentDto assignTeam(UUID incidentId,
                                  AssignTeamRequest request,
                                  UUID assignedBy,
                                  String tenantId) {
        final Incident incident = incidentRepository
                .findByIdAndTenantId(incidentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Incident", incidentId));

        incident.assignToTeam(request.teamId());
        incidentRepository.save(incident);

        log.info("Incident team assigned: incidentId={}, teamId={}, by={}, tenant={}",
                incidentId, request.teamId(), assignedBy, tenantId);

        final IncidentDto dto = IncidentDto.from(incident);
        webSocketPublisher.publishUpdate(dto);

        auditEventPublisher.publishIncidentUser(
                incidentId, tenantId,
                AuditEventTypes.INCIDENT_TEAM_ASSIGNED, SERVICE_NAME,
                assignedBy.toString(),
                String.format("Incident assigned to teamId=%s", request.teamId()),
                Map.of("teamId", request.teamId().toString(),
                        "assignedBy", assignedBy.toString())
        );

        return dto;
    }

    @Transactional
    public IncidentDto unassignTeam(UUID incidentId,
                                    UUID unassignedBy,
                                    String tenantId) {
        final Incident incident = incidentRepository
                .findByIdAndTenantId(incidentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Incident", incidentId));

        final UUID previousTeamId = incident.getTeamId();
        incident.unassignTeam();
        incidentRepository.save(incident);

        log.info("Incident team unassigned: incidentId={}, previousTeamId={}, by={}, tenant={}",
                incidentId, previousTeamId, unassignedBy, tenantId);

        final IncidentDto dto = IncidentDto.from(incident);
        webSocketPublisher.publishUpdate(dto);

        auditEventPublisher.publishIncidentUser(
                incidentId, tenantId,
                AuditEventTypes.INCIDENT_TEAM_UNASSIGNED, SERVICE_NAME,
                unassignedBy.toString(),
                "Incident team assignment removed",
                previousTeamId != null
                        ? Map.of("previousTeamId", previousTeamId.toString())
                        : Map.of()
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

        // Set teamId from Integration-based routing.
        // UnifiedAlertDto.teamId is resolved by ApiKeyLookupServiceImpl
        // from the Integration that authenticated the alert request.
        // Null for JWT-authenticated requests or integrations without team.
        if (alert.teamId() != null) {
            incident.assignToTeam(alert.teamId());
        }

        incidentRepository.save(incident);

        historyRepository.save(IncidentHistory.forCreation(
                incident.getId(), tenantId, ChangeSource.KAFKA_CONSUMER));

        log.info("New incident created: incidentId={}, title='{}', " +
                        "severity={}, tenant={}",
                incident.getId(), incident.getTitle(),
                incident.getSeverity(), tenantId);

        eventPublisher.publishOpened(incident);

        auditEventPublisher.publishIncident(
                incident.getId(), tenantId,
                AuditEventTypes.INCIDENT_CREATED, SERVICE_NAME,
                String.format("Incident created from %s alert: '%s'",
                        alert.source(), alert.title()),
                Map.of("source", alert.source(),
                        "severity", alert.severity().name(),
                        "fingerprint", alert.fingerprint())
        );

        return IncidentDto.from(incident);
    }

    /**
     * Result of processing a duplicate alert — the resulting DTO and whether
     * severity was actually changed, so the caller knows whether a
     * WebSocket update needs to be published.
     */
    private record DuplicateAlertResult(IncidentDto dto, boolean severityChanged) {}

    private DuplicateAlertResult handleDuplicateAlert(UnifiedAlertDto alert,
                                                      String tenantId) {
        final var existingOpt = incidentRepository
                .findActiveByAlertFingerprintAndTenantId(
                        alert.fingerprint(), tenantId);

        if (existingOpt.isEmpty()) {
            log.warn("Race condition in dedup: fingerprint={}, tenant={}",
                    alert.fingerprint(), tenantId);
            // TOCTOU race between the exists() check and this lookup — the
            // incident was deleted/resolved between the two calls. This is
            // genuinely a new incident from the client's perspective, so we
            // create and publish CREATED directly here rather than via the
            // caller's duplicate-branch logic.
            final IncidentDto created = createNewIncident(alert, tenantId);
            webSocketPublisher.publishCreated(created);
            return new DuplicateAlertResult(created, false);
        }

        final Incident existing = existingOpt.get();

        if (!alert.severity().isHigherThan(existing.getSeverity())) {
            return new DuplicateAlertResult(IncidentDto.from(existing), false);
        }

        final String previousSeverity = existing.getSeverity().name();
        existing.updateSeverity(alert.severity());
        incidentRepository.save(existing);

        historyRepository.save(IncidentHistory.forAutomaticChange(
                existing.getId(), tenantId,
                existing.getStatus(), existing.getStatus(),
                ChangeSource.KAFKA_CONSUMER,
                String.format("Severity escalated: %s → %s",
                        previousSeverity, alert.severity().name())
        ));

        log.info("Severity updated: incidentId={}, {} → {}, tenant={}",
                existing.getId(), previousSeverity,
                alert.severity(), tenantId);

        auditEventPublisher.publishIncident(
                existing.getId(), tenantId,
                AuditEventTypes.INCIDENT_SEVERITY_UPDATED, SERVICE_NAME,
                String.format("Severity updated: %s → %s",
                        previousSeverity, alert.severity().name()),
                Map.of("previousSeverity", previousSeverity,
                        "newSeverity", alert.severity().name())
        );

        return new DuplicateAlertResult(IncidentDto.from(existing), true);
    }

    /**
     * Dispatches a REST-API-driven status change to the corresponding domain
     * method on {@link Incident}, rather than calling a generic transitionTo()
     * and separately deciding what side effects to apply here.
     *
     * <p>Each branch delegates entirely to the entity — acknowledge() handles
     * its own auto-assign rule, resolve()/close() are pure status transitions.
     * This service no longer inspects incident state to decide what to do;
     * it only decides *which* domain operation the request maps to.
     *
     * <p>ESCALATED is not a reachable target here — escalation is tracked as
     * an independent attribute (see {@link Incident#getEscalationLevel()}),
     * not a status this endpoint can set. OPEN is also unreachable since
     * IncidentFsm has no transitions back to it. The exhaustive switch over
     * all 4 IncidentStatus values forces a compile error if either is ever
     * added back without updating this dispatcher.
     */
    /**
     * Dispatches a REST-API-driven status change to the corresponding domain
     * method on {@link Incident}, rather than calling a generic transitionTo()
     * and separately deciding what side effects to apply here.
     *
     * <p>Each branch delegates entirely to the entity — acknowledge() handles
     * its own auto-assign rule, resolve()/close() are pure status transitions.
     * This service no longer inspects incident state to decide what to do;
     * it only decides *which* domain operation the request maps to.
     *
     * <h2>Why this is a separate method from {@link #publishStatusChangeEvent}</h2>
     * Both switches map the same {@link IncidentStatus} and could in
     * principle be merged into one — each case would mutate the entity AND
     * publish its Kafka event together, reducing three status-keyed switches
     * (this one, this audit lookup formerly done by resolveAuditEventType(),
     * and the publish dispatch below) down to a single one.
     *
     * <p>They are kept separate because {@link #updateStatus} persists the
     * entity (incidentRepository.save() + historyRepository.save() using
     * the pre-mutation previousStatus) <em>between</em> the mutation and the
     * Kafka publish — the same persist-before-publish ordering used in
     * EscalationScheduler to avoid duplicate-notification risk if the DB
     * save fails. Merging these two switches into one call would either
     * require publishing before the DB commit (reintroducing that risk) or
     * threading the save/history calls awkwardly into the middle of a single
     * switch body (worse readability than two short, clearly-sequenced ones).
     *
     * <p>auditEventType has already been eliminated as a third switch — see
     * {@link IncidentStatus#auditEventType()}.
     *
     * <p>TODO: The principled fix for this whole class of duplication is the
     *  Domain Events pattern: Incident.acknowledge()/resolve()/close() would
     *  internally register a domain event (e.g. IncidentAcknowledgedEvent) on
     *  the entity instead of the caller inferring what happened from the
     *  target status. After save(), the service drains the entity's recorded
     *  events and publishes each one through a uniform dispatcher — no
     *  status-keyed switch needed at all, because the entity itself reports
     *  what occurred. This is a larger architectural change (new event
     *  collection mechanism on Incident, a generic publish-after-save hook)
     *  deferred until it's justified by a second entity needing the same
     *  treatment or by this dispatch logic growing beyond 4 statuses.
     */
    private void applyTransition(Incident incident,
                                 IncidentStatus targetStatus,
                                 UUID changedBy) {
        switch (targetStatus) {
            case ACKNOWLEDGED -> incident.acknowledge(changedBy);
            case RESOLVED     -> incident.resolve();
            case CLOSED       -> incident.close();
            case OPEN         -> throw BusinessException.invalidStatusTransition(
                    incident.getStatus().name(), targetStatus.name());
        }
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
            case OPEN -> log.debug("No event for status: {}", newStatus);
        }
    }
}