package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.SuspensionMode;
import com.incidentplatform.auth.domain.SuspensionReason;
import com.incidentplatform.auth.domain.Tenant;
import com.incidentplatform.auth.domain.TenantStatus;
import com.incidentplatform.auth.repository.AuthTokenRepository;
import com.incidentplatform.auth.repository.TenantRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.ReservedTenants;
import com.incidentplatform.shared.security.TenantIds;
import com.incidentplatform.shared.security.UserPrincipal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Suspends and resumes a tenant (backlog #0-82): a platform operator's action,
 * under the same rule as the rest of the platform API ({@code PlatformAccess},
 * {@code PlatformRateLimiter}), audited in both tenants and alerted.
 *
 * <h2>Two kinds of suspension</h2>
 * <ul>
 *   <li>{@link SuspensionMode#FULL}: nothing works for the tenant's users and
 *       keys, as for a tenant in someone else's hands (#0-90's case) or one in
 *       breach of the terms. Every session and unfinished login of the tenant
 *       ends at once ({@code AuthTokenRepository.invalidateSessionsOfTenant});
 *       sign-ins, invites and API keys are refused while it lasts.</li>
 *   <li>{@link SuspensionMode#READ_ONLY}: reads go on, writes are refused except
 *       account security, as for a billing hold. Sessions stay; a write is 403
 *       {@code TENANT_READ_ONLY} ({@code TenantStatusFilter}); alerts stop, as
 *       filing one is a write.</li>
 * </ul>
 * The mode, the reason and the operator's note can change while the tenant is
 * suspended (read-only to full ends the sessions then). Nothing is deleted or
 * revoked: resumed, the tenant works as before, its API keys included, and its
 * users log in again.
 *
 * <h2>What it does not stop</h2>
 * Until the other services read the status (the second step of #0-82), an
 * access token already issued works in them for up to its 15 minutes, and work
 * already in the platform (alerts in Kafka, notifications, escalations,
 * postmortems) goes on. auth-service refuses the tenant at once.
 *
 * <h2>Why not the operator tenant</h2>
 * {@code platform-operator} (and the legacy {@code system}) are reserved and
 * not managed through this API, as provisioning refuses them: suspending the
 * operator tenant would lock the operators out of the API that resumes it.
 *
 * <h2>Operator-assisted MFA recovery</h2>
 * Deliberately unaffected (#0-90): it is the platform's own action on the
 * tenant, not the tenant's work. A taken-over tenant is typically suspended in
 * full, its admin recovered while it is, and only then resumed.
 */
@Service
public class TenantLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(TenantLifecycleService.class);

    static final int NOTE_MAX = 500;
    static final String SOURCE = "auth-service";
    static final String COUNTER = "platform.tenant.lifecycle";
    /** The actor the customer tenant's trail shows for an operator's step (as #0-90). */
    static final String CUSTOMER_SIDE_OPERATOR = "platform-operator";
    /**
     * How long a suspension or resumption waits for a lock (backlog #0-82, step 2;
     * found in the review of step 1: it waited without bound). Longer than a
     * sign-in's 3 s ({@code TenantAccessService}), as an operator's change is rare
     * and worth waiting for, short enough not to hold a pooled connection.
     */
    static final String LOCK_TIMEOUT = "5s";
    static final Duration BUSY_RETRY_AFTER = Duration.ofSeconds(5);

    private final TenantRepository tenantRepository;
    private final AuthTokenRepository authTokenRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final Counter suspended;
    private final Counter resumed;

    public TenantLifecycleService(TenantRepository tenantRepository,
                                  AuthTokenRepository authTokenRepository,
                                  AuditEventPublisher auditEventPublisher,
                                  MeterRegistry meterRegistry) {
        this.tenantRepository = tenantRepository;
        this.authTokenRepository = authTokenRepository;
        this.auditEventPublisher = auditEventPublisher;
        this.suspended = counter(meterRegistry, "suspended");
        this.resumed = counter(meterRegistry, "resumed");
    }

    private static Counter counter(MeterRegistry registry, String action) {
        return Counter.builder(COUNTER)
                .description("Tenants suspended (or their suspension changed) and resumed by an operator (backlog #0-82)")
                .tag("action", action)
                .register(registry);
    }

    /**
     * Suspends an active tenant, or changes how a suspended one is suspended.
     *
     * <p>{@code SECURITY} only with {@code FULL} (review of #0-82): a taken-over
     * tenant must lose its sessions, and read-only would leave the intruder the
     * account-security writes a billing hold allows (others' MFA, deactivation).
     *
     * <p>Locks the tenant row, then the tenant's session tokens. A rare deadlock
     * with another bulk invalidation of one user's tokens (an MFA reset, a
     * deactivation), which locks them without the tenant row, aborts one of the
     * two (Postgres picks); the loser gets 503 + Retry-After (step 2 of #0-82),
     * nothing half-done remains.
     *
     * @throws BusinessException 400 for a malformed or reserved tenant id, a missing mode or
     *         reason, or an unusable note; 409 if the tenant is being offboarded
     * @throws ResourceNotFoundException if there is no such tenant
     */
    @Transactional
    public void suspend(String tenantId, SuspensionMode mode, SuspensionReason reason, String note,
                        UserPrincipal operator) {
        requireManagedTenant(tenantId);
        if (mode == null || reason == null) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "mode and reason are required",
                    HttpStatus.BAD_REQUEST);
        }
        if (reason == SuspensionReason.SECURITY && mode != SuspensionMode.FULL) {
            // Found in review: read-only keeps sessions and lets an admin reset or
            // disable others' MFA and deactivate them (account security, allowed
            // under a billing hold), which in a taken-over tenant lets the intruder
            // shut the real admins out. A security suspension must end sessions.
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                    "A SECURITY suspension must be FULL: read-only keeps the sessions of whoever took the "
                            + "accounts over", HttpStatus.BAD_REQUEST);
        }
        final String checkedNote = checkedNote(note);
        final Tenant before = lockTenant(tenantId);
        final SuspensionMode previousMode = before.getStatus() == TenantStatus.SUSPENDED
                ? before.getSuspensionMode() : null;

        if (tenantRepository.suspend(tenantId, mode.name(), reason.name(), checkedNote, operator.userId()) != 1) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "Tenant '" + tenantId + "' is " + before.getStatus() + " and cannot be suspended",
                    HttpStatus.CONFLICT);
        }
        int sessionsEnded = 0;
        if (mode == SuspensionMode.FULL && previousMode != SuspensionMode.FULL) {
            sessionsEnded = authTokenRepository.invalidateSessionsOfTenant(tenantId, Instant.now());
        }

        final Map<String, Object> customerSide = new HashMap<>();
        customerSide.put("mode", mode.name());
        customerSide.put("reason", reason.name());
        customerSide.put("previousMode", previousMode == null ? "NONE" : previousMode.name());
        customerSide.put("sessionsEnded", String.valueOf(sessionsEnded));
        final Map<String, Object> operatorSide = new HashMap<>(customerSide);
        operatorSide.put("tenantId", tenantId);
        operatorSide.put("note", checkedNote);
        final String detail = previousMode == null
                ? "Tenant suspended (" + mode + ") by the platform operator"
                : "Tenant suspension changed from " + previousMode + " to " + mode + " by the platform operator";
        auditEventPublisher.publishAuth(operator.userId(), ReservedTenants.PLATFORM_OPERATOR,
                AuditEventTypes.TENANT_SUSPENDED, SOURCE, operator.userId().toString(), detail, operatorSide);
        auditEventPublisher.publishAuth(tenantResource(tenantId), tenantId, AuditEventTypes.TENANT_SUSPENDED,
                SOURCE, CUSTOMER_SIDE_OPERATOR, detail, customerSide);

        CommittedCounters.incrementAfterCommit(suspended);
        log.warn("Tenant suspended: tenant={}, mode={}, reason={}, previousMode={}, sessionsEnded={}, by operator={}",
                tenantId, mode, reason, previousMode, sessionsEnded, operator.userId());
    }

    /**
     * The tenant row, locked for the rest of the transaction ({@code FOR NO KEY
     * UPDATE}, waiting: two operators are serialised), each lock wait of the
     * transaction bounded by {@value #LOCK_TIMEOUT}. Not reset after the
     * lookup, unlike a sign-in's: the session cleanup that follows locks the
     * tenant's token rows, and a wait there is bounded too; whichever lock
     * times out, the operator gets 503 + Retry-After
     * ({@code TenantStatusBusyHandler}), the transaction rolls back and nothing
     * half-done remains.
     */
    private Tenant lockTenant(String tenantId) {
        tenantRepository.setLocalLockTimeout(LOCK_TIMEOUT);
        try {
            return tenantRepository.findByIdForUpdate(tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
        } catch (PessimisticLockingFailureException e) {
            log.warn("Tenant row locked by another change of its status, answered 503: tenant={}", tenantId);
            throw new TenantStatusBusyException(BUSY_RETRY_AFTER);
        }
    }

    /**
     * Resumes a suspended tenant.
     *
     * @throws BusinessException 400 for a malformed or reserved tenant id or an unusable
     *         note; 409 if the tenant is not suspended
     * @throws ResourceNotFoundException if there is no such tenant
     */
    @Transactional
    public void resume(String tenantId, String note, UserPrincipal operator) {
        requireManagedTenant(tenantId);
        final String checkedNote = checkedNote(note);
        final Tenant before = lockTenant(tenantId);
        if (tenantRepository.resume(tenantId) != 1) {
            throw new BusinessException(ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "Tenant '" + tenantId + "' is " + before.getStatus() + ", not suspended", HttpStatus.CONFLICT);
        }

        final Map<String, Object> customerSide = new HashMap<>();
        customerSide.put("previousMode", String.valueOf(before.getSuspensionMode()));
        customerSide.put("previousReason", String.valueOf(before.getSuspensionReason()));
        final Map<String, Object> operatorSide = new HashMap<>(customerSide);
        operatorSide.put("tenantId", tenantId);
        operatorSide.put("note", checkedNote);
        auditEventPublisher.publishAuth(operator.userId(), ReservedTenants.PLATFORM_OPERATOR,
                AuditEventTypes.TENANT_RESUMED, SOURCE, operator.userId().toString(),
                "Tenant resumed by the platform operator", operatorSide);
        auditEventPublisher.publishAuth(tenantResource(tenantId), tenantId, AuditEventTypes.TENANT_RESUMED,
                SOURCE, CUSTOMER_SIDE_OPERATOR, "Tenant resumed by the platform operator", customerSide);

        CommittedCounters.incrementAfterCommit(resumed);
        log.warn("Tenant resumed: tenant={}, was={}, by operator={}", tenantId, before.getSuspensionMode(),
                operator.userId());
    }

    /**
     * The resource id of a tenant-wide event in the customer tenant's trail,
     * which needs one: a name-based UUID of the tenant id, stable across events,
     * so the trail names the tenant and not the operator's account (as #0-90's
     * customer-side events name an operator only as "platform-operator"). The
     * operator tenant's event carries the operator's id, as provisioning does.
     */
    static UUID tenantResource(String tenantId) {
        return UUID.nameUUIDFromBytes(("tenant:" + tenantId).getBytes(StandardCharsets.UTF_8));
    }

    private static void requireManagedTenant(String tenantId) {
        if (!TenantIds.isValid(tenantId)) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                    "tenantId must be 3-63 lowercase letters, digits or hyphens", HttpStatus.BAD_REQUEST);
        }
        if (ReservedTenants.isReserved(tenantId)) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                    "Tenant '" + tenantId + "' is reserved by the platform and cannot be suspended",
                    HttpStatus.BAD_REQUEST);
        }
    }

    /** The note goes into the operator tenant's trail and the API: one line, no forged log lines. */
    private static String checkedNote(String note) {
        if (note == null || note.isBlank() || note.strip().length() > NOTE_MAX
                || note.codePoints().anyMatch(MfaService::isUnsafeInLog)) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED,
                    "note is required: why, at most " + NOTE_MAX
                            + " characters, without control, line-separator or formatting characters",
                    HttpStatus.BAD_REQUEST);
        }
        return note.strip();
    }
}
