package com.incidentplatform.auth.bootstrap;

import com.incidentplatform.auth.bootstrap.TenantAdminReconciler.Outcome;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.repository.AuthTokenRepository;
import com.incidentplatform.auth.repository.TenantRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.auth.service.ResendInviteService;
import com.incidentplatform.auth.service.UserService;
import com.incidentplatform.shared.security.ReservedTenants;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Makes sure the reserved {@link ReservedTenants#PLATFORM_OPERATOR} tenant has
 * an admin who can log in, when {@code platform.operator.bootstrap.admin-email}
 * is set (backlog #0-16, #0-49).
 *
 * <h2>Why an invite</h2>
 * The operator tenant is where the platform's own Alertmanager files its alerts
 * as incidents. Nobody can create it through the API (its id is reserved, and
 * the platform API that provisions customer tenants, backlog #0-80, is itself
 * open only to this tenant's admins), so the
 * platform creates its first user itself, the same way an admin creates any
 * user: {@link UserService#createUser}, which writes the user, the invite token
 * and the invite email in one transaction and audits it. No password ever sits
 * in configuration; the operator sets it by accepting the invite.
 * Alternatives rejected: a Flyway seed with a password from env (a skipped
 * conditional migration is recorded as applied, it cannot use the outbox or
 * audit, and it repeats {@code V1_1}'s plaintext-password weakness), and a
 * platform super-admin endpoint (a cross-tenant capability). Backlog #0-80
 * reversed the second one narrowly for customer tenants: a multi-tenant platform
 * must onboard them at runtime, so this tenant's admins provision them
 * ({@code TenantProvisioningService}); the operator tenant itself still
 * bootstraps here, since nobody could call that API before it has an admin.
 *
 * <h2>Fixed (backlog #0-49): a reconciler, not a one-shot startup runner</h2>
 * This used to be an {@code ApplicationRunner} that did nothing once the tenant
 * had any user. If the invite email failed for good (the outbox gives up after
 * its retries and drops the raw token) or the 7-day token expired unaccepted,
 * nothing could recover: {@code resend-invite} needs an admin of the tenant,
 * and a restart skipped the bootstrap because a user existed. It now checks the
 * goal, not the step, shortly after startup and then every
 * {@code reconcile-interval-ms} (default 1 h), under ShedLock so one replica
 * acts at a time. The goal-checking logic is {@link TenantAdminReconciler},
 * shared since backlog #0-80 with {@code TenantProvisioningService}, which uses
 * it to reissue a provisioned tenant's first admin invite; its Javadoc lists
 * the cases. A conflict here is fixed by hand as README Step 5 describes.
 * Until an admin can log in, the {@code platform.operator.admin.pending} gauge
 * is 1 and {@code OperatorAdminNotActivated} (docker/prometheus.rules.yml)
 * fires after an hour. The gauge is refreshed by {@link #refreshPendingGauge()}
 * on <em>every</em> replica, without the lock, every
 * {@code gauge-refresh-interval-ms} (default 5 min): only the replica that wins
 * the lock runs {@link #scheduledReconcile()}, so a gauge set only there would
 * leave the other replicas reporting a stale value, and the rule's {@code max()}
 * could keep the alert firing after the problem was fixed (found in review). That alert reaches the operator through Alertmanager's
 * own email route, which does not depend on auth-service's mail settings.
 * Alternative rejected: re-inviting only at startup (a pod can run for weeks,
 * and every replica would race at boot).
 *
 * <p>The Integration API key for Alertmanager is not created here: the operator
 * admin creates it with {@code POST /api/v1/integrations} and stores it as a
 * secret, so no service writes credentials to disk.
 */
@Component
public class OperatorTenantBootstrap {

    private static final Logger log = LoggerFactory.getLogger(OperatorTenantBootstrap.class);

    static final String PENDING_GAUGE = "platform.operator.admin.pending";

    static final String DISPLAY_NAME = "Platform operator";

    private final TenantAdminReconciler reconciler;
    private final TenantRepository tenantRepository;
    private final String adminEmail;
    private final AtomicInteger pending = new AtomicInteger(0);

    public OperatorTenantBootstrap(
            @Value("${platform.operator.bootstrap.admin-email:}") String adminEmail,
            UserRepository userRepository,
            AuthTokenRepository authTokenRepository,
            AuthEmailOutboxRepository outboxRepository,
            UserService userService,
            ResendInviteService resendInviteService,
            TenantRepository tenantRepository,
            MeterRegistry meterRegistry) {
        this.tenantRepository = tenantRepository;
        this.adminEmail = adminEmail == null ? "" : adminEmail.trim();
        this.reconciler = new TenantAdminReconciler(
                ReservedTenants.PLATFORM_OPERATOR, adminEmail, "Operator tenant", "README Step 5",
                userRepository, authTokenRepository, outboxRepository, userService,
                resendInviteService);
        if (reconciler.enabled()) {
            // Registered only when the bootstrap is enabled, so a deployment
            // without an operator tenant never reports it as pending.
            Gauge.builder(PENDING_GAUGE, pending, AtomicInteger::get)
                    .description("1 while the platform-operator tenant has no admin "
                            + "who can log in (backlog #0-49), else 0")
                    .register(meterRegistry);
        }
    }

    @Scheduled(
            initialDelayString = "${platform.operator.bootstrap.initial-delay-ms:30000}",
            fixedDelayString = "${platform.operator.bootstrap.reconcile-interval-ms:3600000}")
    @SchedulerLock(name = "auth-service:reconcileOperatorAdmin", lockAtMostFor = "5m")
    public void scheduledReconcile() {
        reconcile();
    }

    /**
     * Read-only: sets the gauge from the database on this replica. Deliberately
     * not under ShedLock (see the class Javadoc). One indexed EXISTS query per
     * replica per interval.
     */
    @Scheduled(
            initialDelayString = "${platform.operator.bootstrap.initial-delay-ms:30000}",
            fixedDelayString = "${platform.operator.bootstrap.gauge-refresh-interval-ms:300000}")
    public void refreshPendingGauge() {
        if (!reconciler.enabled()) {
            return;
        }
        try {
            pending.set(reconciler.adminCanLogIn() ? 0 : 1);
        } catch (RuntimeException e) {
            // Keep the last known value: a database outage has alerts of its
            // own, and flipping to "pending" would page for the wrong cause.
            log.warn("Could not refresh {}; keeping the last value", PENDING_GAUGE, e);
        }
    }

    Outcome reconcile() {
        if (reconciler.enabled() && !recordTenant()) {
            // Keep the gauge's last value, as refreshPendingGauge does: the
            // cause is the database, which has alerts of its own.
            return Outcome.FAILED;
        }
        final Outcome outcome = reconciler.reconcile();
        if (outcome != Outcome.DISABLED) {
            pending.set(outcome == Outcome.ADMIN_ACTIVE ? 0 : 1);
        }
        return outcome;
    }

    /**
     * Records the operator tenant in {@code tenants} (backlog #0-80), like every
     * provisioned tenant: on a new database V21 ran before any user existed, so
     * its backfill could not. Idempotent; the next run retries a failure.
     *
     * <p>Backlog #0-82: a failure now stops the run. It used to be logged and
     * the admin reconciliation went on, which mattered more; since V31 every
     * user needs its tenant's row, so inviting the admin without it could only
     * fail on the foreign key.
     *
     * @return whether the row exists now
     */
    private boolean recordTenant() {
        try {
            if (tenantRepository.insertIfAbsent(ReservedTenants.PLATFORM_OPERATOR, DISPLAY_NAME,
                    adminEmail, null) == 1) {
                log.info("Operator tenant recorded in tenants, tenant={}",
                        ReservedTenants.PLATFORM_OPERATOR);
            }
            return true;
        } catch (RuntimeException e) {
            log.error("Could not record the operator tenant in tenants; the admin reconciliation "
                    + "waits for the next run", e);
            return false;
        }
    }
}
