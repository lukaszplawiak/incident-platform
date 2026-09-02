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
import com.incidentplatform.shared.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
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
    private final IncidentCreationService incidentCreationService;

    public IncidentCommandService(
            IncidentRepository incidentRepository,
            IncidentHistoryRepository historyRepository,
            IncidentEventPublisher eventPublisher,
            IncidentWebSocketPublisher webSocketPublisher,
            AuditEventPublisher auditEventPublisher,
            IncidentCreationService incidentCreationService) {
        this.incidentRepository = incidentRepository;
        this.historyRepository = historyRepository;
        this.eventPublisher = eventPublisher;
        this.webSocketPublisher = webSocketPublisher;
        this.auditEventPublisher = auditEventPublisher;
        this.incidentCreationService = incidentCreationService;
    }

    /**
     * <h2>Fixed (backlog #74): falls through to duplicate handling on a
     * lost creation race</h2>
     * The "no duplicate exists" branch now delegates to
     * {@link IncidentCreationService#tryCreate}, which throws
     * {@link org.springframework.dao.DataIntegrityViolationException} if
     * another replica concurrently created the incident first (see that
     * class's own Javadoc, and migration V11, for the full account — and
     * for why the exception is deliberately left to propagate rather than
     * caught inside {@code tryCreate} itself). On that outcome, this
     * method falls through to exactly the same {@link #handleDuplicateAlert}
     * path already used for a duplicate detected by the initial
     * {@code existsActiveByTenantIdAndAlertFingerprint} check — from this
     * point on, "lost the race" and "was already a duplicate" are
     * indistinguishable and should be handled identically.
     */
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
            return;
        }

        try {
            final IncidentDto created = incidentCreationService.tryCreate(alert, tenantId);
            webSocketPublisher.publishCreated(created);
        } catch (DataIntegrityViolationException e) {
            // Lost the race — someone else's tryCreate() won in the meantime.
            // Safe to catch here: this exception has already fully unwound
            // incidentCreationService.tryCreate's own, separate REQUIRES_NEW
            // transaction by the time it reaches this catch block — this
            // method's own, suspended-then-resumed outer transaction was
            // never touched. Re-read and treat exactly like a duplicate
            // detected up front.
            final DuplicateAlertResult result = handleDuplicateAlert(alert, tenantId);
            if (result.severityChanged()) {
                webSocketPublisher.publishUpdate(result.dto());
            }
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

        final Incident incident = requireIncident(incidentId, tenantId);
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

    /**
     * Fixed (backlog #76): {@code assignTo}/{@code assignTeam}/
     * {@code unassignTeam} previously each repeated the identical
     * load-or-404 / save / build DTO / publish WebSocket update sequence
     * inline. Extracted to {@link #requireIncident} and
     * {@link #saveAndPublishUpdate} — the one thing deliberately NOT
     * extracted alongside them is the audit-event publish, since its
     * event type, message, and metadata genuinely differ per method (and
     * {@link #unassignTeam} specifically needs to read
     * {@code incident.getTeamId()} <em>before</em> mutating it, for its
     * "previousTeamId" audit field — easy with the incident staying a
     * plain local variable in each caller, awkward if mutation were
     * pushed into a shared helper via a passed-in lambda instead).
     */
    @Transactional
    public IncidentDto assignTo(UUID incidentId, UUID assignToId,
                                UUID assignedBy, String tenantId) {
        final Incident incident = requireIncident(incidentId, tenantId);
        incident.assignTo(assignToId);
        final IncidentDto dto = saveAndPublishUpdate(incident);

        log.info("Incident assigned: incidentId={}, assignedTo={}, assignedBy={}, tenant={}",
                incidentId, assignToId, assignedBy, tenantId);

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


    /**
     * Assigns a team to an incident.
     *
     * <p>ROLE_ADMIN can assign any team. Everyone else must be a member of
     * the target team ({@link UserPrincipal#isMemberOf}, populated from the
     * JWT {@code teamIds} claim) — otherwise throws
     * {@link BusinessException#notTeamMember} (403).
     *
     * <p>This check previously didn't exist at all — any authenticated
     * RESPONDER, regardless of team membership, could assign any incident
     * to any team. Found while adding security test coverage for
     * IncidentController.
     *
     * <p>See {@link #assignTo}'s own Javadoc (backlog #76) for why the
     * load/save/publish sequence below is split across
     * {@link #requireIncident} and {@link #saveAndPublishUpdate}.
     */
    @Transactional
    public IncidentDto assignTeam(UUID incidentId,
                                  AssignTeamRequest request,
                                  UserPrincipal principal,
                                  String tenantId) {
        if (!principal.hasRole("ROLE_ADMIN") && !principal.isMemberOf(request.teamId())) {
            throw BusinessException.notTeamMember(request.teamId());
        }

        final UUID assignedBy = principal.userId();

        final Incident incident = requireIncident(incidentId, tenantId);
        incident.assignToTeam(request.teamId());
        final IncidentDto dto = saveAndPublishUpdate(incident);

        log.info("Incident team assigned: incidentId={}, teamId={}, by={}, tenant={}",
                incidentId, request.teamId(), assignedBy, tenantId);

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

    /**
     * Unassigns an incident's team.
     *
     * <p>ROLE_ADMIN can unassign any incident. Everyone else must be a
     * member of the incident's <em>current</em> team — checked against
     * {@code incident.getTeamId()} after it's loaded, since that's the
     * team membership actually being revoked, not a team named anywhere
     * in the request (there is no request body for this endpoint).
     * Throws {@link BusinessException#notTeamMember} (403) otherwise.
     *
     * <p>Same previously-missing check as {@link #assignTeam} — see that
     * method's Javadoc for context.
     *
     * <p>See {@link #assignTo}'s own Javadoc (backlog #76) for why the
     * load/save/publish sequence below is split across
     * {@link #requireIncident} and {@link #saveAndPublishUpdate} — and
     * specifically why the incident stays a plain local variable here
     * rather than that split being pushed further into a shared mutation
     * step: {@code previousTeamId} below must be read <em>before</em>
     * {@link Incident#unassignTeam()} runs.
     */
    @Transactional
    public IncidentDto unassignTeam(UUID incidentId,
                                    UserPrincipal principal,
                                    String tenantId) {
        final Incident incident = requireIncident(incidentId, tenantId);

        final UUID currentTeamId = incident.getTeamId();
        if (currentTeamId != null
                && !principal.hasRole("ROLE_ADMIN")
                && !principal.isMemberOf(currentTeamId)) {
            throw BusinessException.notTeamMember(currentTeamId);
        }

        final UUID unassignedBy = principal.userId();
        final UUID previousTeamId = incident.getTeamId();
        incident.unassignTeam();
        final IncidentDto dto = saveAndPublishUpdate(incident);

        log.info("Incident team unassigned: incidentId={}, previousTeamId={}, by={}, tenant={}",
                incidentId, previousTeamId, unassignedBy, tenantId);

        auditEventPublisher.publishIncidentUser(
                incidentId, tenantId,
                AuditEventTypes.INCIDENT_TEAM_UNASSIGNED, SERVICE_NAME,
                unassignedBy.toString(),
                String.format("Incident unassigned from teamId=%s", previousTeamId),
                Map.of("previousTeamId", previousTeamId != null ? previousTeamId.toString() : "null",
                        "unassignedBy", unassignedBy.toString())
        );

        return dto;
    }

    /**
     * Loads an incident by ID, scoped to the tenant, or throws
     * {@link ResourceNotFoundException} (404) — backlog #76, previously
     * repeated identically in {@link #updateStatus}, {@link #assignTo},
     * {@link #assignTeam}, and {@link #unassignTeam}.
     */
    private Incident requireIncident(UUID incidentId, String tenantId) {
        return incidentRepository
                .findByIdAndTenantId(incidentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Incident", incidentId));
    }

    /**
     * Persists an already-mutated incident, builds its DTO, and publishes
     * a WebSocket update — backlog #76, previously repeated identically in
     * {@link #assignTo}, {@link #assignTeam}, and {@link #unassignTeam}.
     * Deliberately NOT used by {@link #updateStatus}: that method's own
     * save/publish sequence genuinely differs (a history entry is saved
     * between the mutation and the DTO build, it publishes a Kafka event
     * via {@link #publishStatusChangeEvent} in addition to WebSocket, and
     * it calls {@code webSocketPublisher.publishStatusChanged} — a
     * different method — not {@code publishUpdate}), so forcing it through
     * this same helper would not actually remove duplication, only hide a
     * real behavioral difference behind a shared name.
     */
    private IncidentDto saveAndPublishUpdate(Incident incident) {
        incidentRepository.save(incident);
        final IncidentDto dto = IncidentDto.from(incident);
        webSocketPublisher.publishUpdate(dto);
        return dto;
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
            // attempt to create it directly here rather than via the
            // caller's duplicate-branch logic.
            //
            // Fixed (backlog #74): now goes through the same
            // incidentCreationService.tryCreate(...) as the primary
            // create-new path, rather than the old, deleted private
            // createNewIncident(...) helper — extends the same
            // uq_incidents_active_tenant_fingerprint race protection to
            // this rarer nested-race branch too. incidentCreationService
            // is a genuinely different Spring bean, so calling it from
            // here is a real, externally-proxied call, not the
            // self-invocation that would silently ignore its
            // @Transactional(REQUIRES_NEW) — see that class's own Javadoc.
            try {
                final IncidentDto created =
                        incidentCreationService.tryCreate(alert, tenantId);
                webSocketPublisher.publishCreated(created);
                return new DuplicateAlertResult(created, false);
            } catch (DataIntegrityViolationException e) {
                // Exceptionally unlikely: lost this nested race too. One
                // more read — whoever won must now be visible. Not retried
                // further; two concurrent creators both losing to a third
                // within the same request is not a realistic scenario
                // worth building unbounded retry logic for.
                final Incident winner = incidentRepository
                        .findActiveByAlertFingerprintAndTenantId(
                                alert.fingerprint(), tenantId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Lost two consecutive incident-creation races for the " +
                                        "same fingerprint but no winning incident is " +
                                        "visible: fingerprint=" + alert.fingerprint() +
                                        ", tenant=" + tenantId));
                return new DuplicateAlertResult(IncidentDto.from(winner), false);
            }
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