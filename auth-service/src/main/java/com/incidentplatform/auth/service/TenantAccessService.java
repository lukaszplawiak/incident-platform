package com.incidentplatform.auth.service;

import com.incidentplatform.auth.repository.TenantRepository;
import com.incidentplatform.auth.repository.TenantStatusView;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.security.TenantAccess;
import com.incidentplatform.shared.security.TenantAccessState;
import com.incidentplatform.shared.security.TenantStatusProvider;
import com.incidentplatform.auth.domain.SuspensionMode;
import com.incidentplatform.auth.domain.TenantStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a tenant may do, from its status in auth-service's own {@code tenants}
 * table (backlog #0-82).
 *
 * <p>Two uses. As the {@link TenantStatusProvider} of auth-service's filter
 * chain ({@code TenantStatusFilter}, in {@code shared}), which refuses the
 * requests of a suspended tenant's signed-in users and API keys. And as the
 * guard of the public paths, which have no principal for the filter to check
 * and so check the tenant themselves: logging in, refreshing, finishing an MFA
 * login ({@link #requireCanSignIn}), accepting an invite
 * ({@link #requireCanWrite}).
 *
 * <p>One primary-key lookup per call, not cached: a suspension takes effect on
 * the next request, which is the point of it, and auth-service's own requests
 * are not hot enough for the lookup to matter.
 *
 * <p>A tenant without a row has full access rather than none, and cannot be
 * suspended (suspend finds no row, 404). Since V31 (backlog #0-82) such a
 * tenant has no data in auth-service either: every table holding a tenant's
 * data has a foreign key to {@code tenants}, so no user, key or session can
 * exist before the row. What is left is a token naming a tenant id that
 * auth-service never recorded: auth-service signs tokens only for its own
 * users, so in a deployment only a token minted elsewhere with the shared
 * secret can (incident-service's dev-profile {@code /dev/token} does, for any
 * id, backlog #0-63). Full access stays the answer, not none, so a
 * gap cannot lock a tenant out, and it stays a tripwire: counted on every
 * lookup ({@value #MISSING_ROW_COUNTER}, alert
 * {@code PlatformTenantStatusRowMissing}) and logged at WARN once per tenant,
 * because outside the dev profile it means a signing key or token issuer that
 * should not exist.
 */
@Service
public class TenantAccessService implements TenantStatusProvider {

    private static final Logger log = LoggerFactory.getLogger(TenantAccessService.class);

    static final String MISSING_ROW_COUNTER = "platform.tenant.status.missing";
    /** How long a sign-in waits for a suspension of its tenant in progress (backlog #0-82). */
    static final String SIGN_IN_LOCK_TIMEOUT = "3s";
    static final Duration SIGN_IN_RETRY_AFTER = Duration.ofSeconds(5);
    /** Bounds the set of tenants already warned about; tenants are few, ids come from authentication. */
    static final int WARNED_MAX = 1_000;

    private final TenantRepository tenantRepository;
    private final Counter missingRows;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public TenantAccessService(TenantRepository tenantRepository, MeterRegistry meterRegistry) {
        this.tenantRepository = tenantRepository;
        this.missingRows = Counter.builder(MISSING_ROW_COUNTER)
                .description("Status lookups of a tenant without a tenants row: full access, not suspendable "
                        + "(backlog #0-82)")
                .register(meterRegistry);
    }

    @Override
    public TenantAccess accessOf(String tenantId) {
        return stateOf(tenantId).access();
    }

    /**
     * What the tenant may do, with when it was suspended (backlog #0-82, step
     * 2b): the other services' answer ({@code /api/v1/internal/tenant-status}),
     * so that a service pausing the tenant's background work measures the pause
     * from the suspension itself, not from when it noticed it. No time for full
     * access, or for a tenant without a {@code tenants} row.
     */
    public TenantAccessState stateOf(String tenantId) {
        final Optional<TenantStatusView> view = tenantRepository.findStatus(tenantId);
        final TenantAccess access = access(tenantId, view);
        return new TenantAccessState(access,
                access == TenantAccess.FULL ? null : view.map(TenantStatusView::suspendedAt).orElse(null));
    }

    @Override
    public Optional<TenantAccessState> confirmedStateOf(String tenantId) {
        return Optional.of(stateOf(tenantId));
    }

    private TenantAccess access(String tenantId, Optional<TenantStatusView> view) {
        if (view.isEmpty()) {
            // Counted every time (alert PlatformTenantStatusRowMissing), logged once
            // per tenant: this runs on every request of that tenant.
            missingRows.increment();
            if (warned.size() < WARNED_MAX && warned.add(tenantId)) {
                log.warn("Tenant has no row in tenants, given full access and not suspendable: tenant={}",
                        tenantId);
            }
            return TenantAccess.FULL;
        }
        return switch (view.get().status()) {
            case ACTIVE -> TenantAccess.FULL;
            case SUSPENDED -> view.get().mode().access();
            // Reserved for offboarding: nothing works for a tenant on its way out.
            case OFFBOARDING, OFFBOARDED -> TenantAccess.NONE;
        };
    }

    /**
     * Refuses a sign-in (password login, refresh, finishing an MFA login) for a
     * tenant suspended in full. A read-only tenant signs in: reading is what it
     * is left with. 403 {@code TENANT_SUSPENDED}.
     *
     * <p>Share-locks the tenant row until the caller's transaction ends
     * ({@link TenantRepository#findStatusForSignIn}), so a session it creates
     * cannot slip past a concurrent suspension; hence {@code MANDATORY}. Call
     * it before consuming any token a suspension ends (REFRESH, MFA_SESSION,
     * MFA_SETUP_REQUIRED: tenant row first, token rows second, the order a
     * suspension locks them in), and after any slow work such as a password
     * hash, as the lock is held until commit. Invite and reset consume their
     * token first, which is safe only because a suspension never locks those
     * types ({@code AuthTokenRepository.invalidateSessionsOfTenant}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCanSignIn(String tenantId, UUID userId, SignInFlow flow) {
        final TenantAccess access = lockedAccessOf(tenantId);
        if (access == TenantAccess.NONE) {
            // Its own type (step 2): audited and counted once the transaction
            // has rolled back (SignInRefusalHandler).
            throw new TenantSuspendedSignInException(tenantId, userId, flow, access);
        }
    }

    /**
     * Refuses a sign-in that is also a write (accepting an invite) for a
     * suspended tenant, in full or read-only, audited and counted like
     * {@link #requireCanSignIn}. Share-locks the tenant row, as that does.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCanJoin(String tenantId, UUID userId, SignInFlow flow) {
        final TenantAccess access = lockedAccessOf(tenantId);
        if (access != TenantAccess.FULL) {
            throw new TenantSuspendedSignInException(tenantId, userId, flow, access);
        }
    }

    /**
     * Refuses a write for a suspended tenant, in full or read-only, where the
     * filter does not already (an admin's request that also takes a
     * non-security write, such as reactivating a user). Not a sign-in, so not
     * audited as one. Share-locks the tenant row, as {@link #requireCanSignIn}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCanWrite(String tenantId) {
        final TenantAccess access = lockedAccessOf(tenantId);
        if (access == TenantAccess.NONE) {
            throw suspended();
        }
        if (access == TenantAccess.READ_ONLY) {
            throw new BusinessException(ErrorCodes.TENANT_READ_ONLY,
                    "This organisation's account is suspended to read-only: changes are refused until it "
                            + "is resumed.", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * The share-locked lookup, waiting at most {@value #SIGN_IN_LOCK_TIMEOUT}
     * for a suspension or resumption of the tenant in progress. Past that the
     * sign-in is answered 503 + Retry-After ({@link TenantStatusBusyException}),
     * not held on a pooled connection; the transaction is rolled back, so
     * nothing it consumed is lost.
     */
    private TenantAccess lockedAccessOf(String tenantId) {
        tenantRepository.setLocalLockTimeout(SIGN_IN_LOCK_TIMEOUT);
        final Optional<TenantRepository.LockedTenantStatus> row;
        try {
            row = tenantRepository.findStatusForSignIn(tenantId);
        } catch (PessimisticLockingFailureException e) {
            // No reset here: the failed statement aborted the transaction, which
            // rolls back and takes the SET LOCAL with it.
            log.warn("Tenant status locked by a suspension in progress, sign-in answered 503: tenant={}", tenantId);
            throw new TenantStatusBusyException(SIGN_IN_RETRY_AFTER);
        }
        tenantRepository.resetLocalLockTimeout();
        return access(tenantId, row.map(r -> new TenantStatusView(TenantStatus.valueOf(r.getStatus()),
                r.getMode() == null ? null : SuspensionMode.valueOf(r.getMode()), null)));
    }

    private static BusinessException suspended() {
        return new BusinessException(ErrorCodes.TENANT_SUSPENDED,
                "This organisation's account is suspended. Contact the platform operator.", HttpStatus.FORBIDDEN);
    }
}
