package com.incidentplatform.incident.service;

import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.incident.domain.IncidentHistory;
import com.incidentplatform.incident.dto.IncidentDto;
import com.incidentplatform.incident.repository.IncidentHistoryRepository;
import com.incidentplatform.incident.repository.IncidentRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.audit.ChangeSource;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Attempts to create a new {@link Incident} from a firing alert, isolated
 * in its own transaction so a concurrent-creation conflict rolls back
 * cleanly without poisoning the caller's own transaction.
 *
 * <h2>Fixed (backlog #74): check-then-act race in incident creation</h2>
 * {@link IncidentCommandService#createFromAlert} checks
 * {@code existsActiveByTenantIdAndAlertFingerprint} (a read) and, if
 * false, used to create a new {@link Incident} directly (a write) — with
 * no atomic protection between the two. Kafka's tenant-keyed partitioning
 * ({@code AlertKafkaProducer} keys by {@code tenantId}, not fingerprint)
 * naturally serializes one tenant's messages through a single consumer
 * thread under normal, single-replica operation, but with
 * {@code incident-service} horizontally scaled to multiple replicas and a
 * partition rebalance occurring while a message is in-flight, two
 * replicas could both see "no active incident yet" and both create one —
 * see migration V11's own comment for the full account of why that's
 * worse than just a confusing duplicate ({@code autoResolve} assumes at
 * most one active row per fingerprint).
 *
 * <h2>Why this is a separate class/bean, not just a new method on
 * {@link IncidentCommandService}</h2>
 * {@link #tryCreate} uses {@code @Transactional(propagation = REQUIRES_NEW)}
 * to guarantee a genuinely independent transaction every time — but
 * {@code REQUIRES_NEW} only takes effect through Spring's AOP proxy,
 * which self-invocation (calling another method on {@code this} from
 * within the same class) bypasses entirely. Being a distinct Spring bean,
 * injected into and called from {@link IncidentCommandService}, is what
 * makes the proxy — and therefore the independent transaction — real.
 *
 * <h2>Why the constraint violation is left to propagate, not caught here</h2>
 * PostgreSQL marks a transaction as aborted the moment a statement inside
 * it violates a constraint. An earlier version of this method caught
 * {@link DataIntegrityViolationException} internally and returned
 * {@code Optional.empty()} — but that required explicitly calling
 * {@code TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()}
 * to avoid relying on implicit JDBC-driver behavior when committing an
 * already-aborted transaction, and that call throws
 * {@code NoTransactionException} outside a real, Spring-managed
 * transactional context — making this method impossible to unit-test
 * with a plain Mockito test (no Spring proxy, no bound transaction).
 * Leaving the exception uncaught is both simpler and correct: Spring's
 * own {@code @Transactional} interceptor around this method already
 * rolls back automatically on any unchecked exception propagating out —
 * no manual rollback signaling needed — and the caller
 * ({@link IncidentCommandService#createFromAlert}) simply catches
 * {@link DataIntegrityViolationException} around its call to this method,
 * which is safe precisely because that exception has already fully
 * unwound this method's own, separate {@code REQUIRES_NEW} transaction
 * by the time it reaches the caller — the caller's own, suspended-then-
 * resumed outer transaction was never touched.
 *
 * <h2>Recovery on conflict</h2>
 * Throws {@link DataIntegrityViolationException} when
 * {@code uq_incidents_active_tenant_fingerprint} (migration V11) rejects
 * the insert — meaning another replica won the race and already created
 * the active incident this alert belongs to. The caller treats this
 * exactly like the pre-existing duplicate-alert path: re-reads the
 * now-committed winner and applies the same severity-escalation logic
 * {@code handleDuplicateAlert} already applies to any other duplicate.
 */
@Service
public class IncidentCreationService {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentCreationService.class);

    private static final String SERVICE_NAME = "incident-service";

    private final IncidentRepository incidentRepository;
    private final IncidentHistoryRepository historyRepository;
    private final IncidentEventPublisher eventPublisher;
    private final AuditEventPublisher auditEventPublisher;

    public IncidentCreationService(
            IncidentRepository incidentRepository,
            IncidentHistoryRepository historyRepository,
            IncidentEventPublisher eventPublisher,
            AuditEventPublisher auditEventPublisher) {
        this.incidentRepository = incidentRepository;
        this.historyRepository = historyRepository;
        this.eventPublisher = eventPublisher;
        this.auditEventPublisher = auditEventPublisher;
    }

    /**
     * @return the created incident's DTO
     * @throws DataIntegrityViolationException if
     *         {@code uq_incidents_active_tenant_fingerprint} rejected the
     *         insert because another replica concurrently created it first —
     *         see this class's own Javadoc for why it's intentionally left
     *         to propagate rather than caught here
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IncidentDto tryCreate(UnifiedAlertDto alert, String tenantId) {
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

        // saveAndFlush, not save — the constraint violation must surface
        // synchronously, right here, rather than being deferred to this
        // transaction's own commit (REQUIRES_NEW still means Hibernate is
        // free to batch the flush until commit unless forced) — deferring
        // it would mean it surfaces somewhere far less useful, at commit
        // time, after this method has already returned.
        incidentRepository.saveAndFlush(incident);

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
}