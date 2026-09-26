package com.incidentplatform.auth.bootstrap;

import com.incidentplatform.auth.domain.AuthEmailOutbox;
import com.incidentplatform.auth.domain.AuthEmailType;
import com.incidentplatform.auth.domain.AuthToken;
import com.incidentplatform.auth.domain.Role;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.CreateUserRequest;
import com.incidentplatform.auth.repository.AuthEmailOutboxRepository;
import com.incidentplatform.auth.repository.AuthTokenRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.auth.service.ResendInviteService;
import com.incidentplatform.auth.service.UserService;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.security.ReservedTenants;
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.TenantContext;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Makes sure the reserved {@link ReservedTenants#PLATFORM_OPERATOR} tenant has
 * an admin who can log in, when {@code platform.operator.bootstrap.admin-email}
 * is set (backlog #0-16, #0-49).
 *
 * <h2>Why an invite</h2>
 * The operator tenant is where the platform's own Alertmanager files its alerts
 * as incidents. Nobody can create it through the API (tenant ids are reserved,
 * and there is deliberately no cross-tenant "create tenant" endpoint), so the
 * platform creates its first user itself, the same way an admin creates any
 * user: {@link UserService#createUser}, which writes the user, the invite token
 * and the invite email in one transaction and audits it. No password ever sits
 * in configuration; the operator sets it by accepting the invite.
 * Alternatives rejected: a Flyway seed with a password from env (a skipped
 * conditional migration is recorded as applied, it cannot use the outbox or
 * audit, and it repeats {@code V1_1}'s plaintext-password weakness), and a
 * platform super-admin endpoint (a cross-tenant capability).
 *
 * <h2>Fixed (backlog #0-49): a reconciler, not a one-shot startup runner</h2>
 * This used to be an {@code ApplicationRunner} that did nothing once the tenant
 * had any user. If the invite email failed for good (the outbox gives up after
 * its retries and drops the raw token) or the 7-day token expired unaccepted,
 * nothing could recover: {@code resend-invite} needs an admin of the tenant,
 * and a restart skipped the bootstrap because a user existed. It now checks the
 * goal, not the step, shortly after startup and then every
 * {@code reconcile-interval-ms} (default 1 h), under ShedLock so one replica
 * acts at a time:
 * <ul>
 *   <li>an active admin with a password exists: done;</li>
 *   <li>the tenant has no user: invite the configured address;</li>
 *   <li>the configured admin has not accepted, and their latest invite
 *       permanently failed or they hold no valid invite token: re-invite through
 *       {@link ResendInviteService} (invalidates old tokens, audited);</li>
 *   <li>their invite is still being sent, or was sent and is still valid: wait,
 *       so the admin is not sent a new email every hour;</li>
 *   <li>the tenant's only users are not the configured address, or the
 *       configured user cannot log in as an active admin: log an ERROR and do
 *       nothing. A second admin is never created and no user is removed; a
 *       human fixes the data (README Step 5).</li>
 * </ul>
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

    /** What one reconciliation found or did. */
    enum Outcome {
        DISABLED,
        ADMIN_ACTIVE,
        INVITED,
        REINVITED,
        INVITE_IN_PROGRESS,
        CONFLICT,
        FAILED
    }

    private final String adminEmail;
    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final AuthEmailOutboxRepository outboxRepository;
    private final UserService userService;
    private final ResendInviteService resendInviteService;
    private final AtomicInteger pending = new AtomicInteger(0);

    public OperatorTenantBootstrap(
            @Value("${platform.operator.bootstrap.admin-email:}") String adminEmail,
            UserRepository userRepository,
            AuthTokenRepository authTokenRepository,
            AuthEmailOutboxRepository outboxRepository,
            UserService userService,
            ResendInviteService resendInviteService,
            MeterRegistry meterRegistry) {
        this.adminEmail = adminEmail == null ? "" : adminEmail.trim();
        this.userRepository = userRepository;
        this.authTokenRepository = authTokenRepository;
        this.outboxRepository = outboxRepository;
        this.userService = userService;
        this.resendInviteService = resendInviteService;
        if (!this.adminEmail.isEmpty()) {
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
        if (adminEmail.isEmpty()) {
            return;
        }
        try {
            pending.set(userRepository.existsActiveAcceptedUserWithRole(
                    ReservedTenants.PLATFORM_OPERATOR, Role.ROLE_ADMIN) ? 0 : 1);
        } catch (RuntimeException e) {
            // Keep the last known value: a database outage has alerts of its
            // own, and flipping to "pending" would page for the wrong cause.
            log.warn("Could not refresh {}; keeping the last value", PENDING_GAUGE, e);
        }
    }

    /**
     * One reconciliation. Never throws: this runs from a scheduler, so a failure
     * is logged at ERROR, reported through the gauge, and retried next run.
     */
    Outcome reconcile() {
        if (adminEmail.isEmpty()) {
            log.debug("Operator tenant bootstrap disabled (platform.operator.bootstrap.admin-email unset)");
            return Outcome.DISABLED;
        }
        TenantContext.set(ReservedTenants.PLATFORM_OPERATOR);
        try {
            final Outcome outcome = reconcileEnabled();
            pending.set(outcome == Outcome.ADMIN_ACTIVE ? 0 : 1);
            return outcome;
        } catch (RuntimeException e) {
            pending.set(1);
            log.error("Operator admin reconciliation failed; retrying next run, tenant={}",
                    ReservedTenants.PLATFORM_OPERATOR, e);
            return Outcome.FAILED;
        } finally {
            TenantContext.clear();
        }
    }

    private Outcome reconcileEnabled() {
        final String tenant = ReservedTenants.PLATFORM_OPERATOR;
        if (userRepository.existsActiveAcceptedUserWithRole(tenant, Role.ROLE_ADMIN)) {
            log.debug("Operator tenant has an active admin — nothing to do");
            return Outcome.ADMIN_ACTIVE;
        }

        final Optional<User> configured = userRepository.findByEmailAndTenantId(adminEmail, tenant);
        if (configured.isEmpty()) {
            if (userRepository.existsByTenantId(tenant)) {
                log.error("Operator tenant has no admin who can log in, and its users do not "
                                + "include the configured admin email — not creating a second "
                                + "admin. Fix the users of tenant={} (README Step 5)", tenant);
                return Outcome.CONFLICT;
            }
            userService.createUser(new CreateUserRequest(
                    adminEmail, List.of(SecurityRoles.ROLE_ADMIN)));
            log.info("Operator tenant bootstrapped: invite queued for the first admin, tenant={}",
                    tenant);
            return Outcome.INVITED;
        }

        final User user = configured.get();
        if (user.getPasswordHash() != null || !user.isActive()
                || !user.getRoleNames().contains(SecurityRoles.ROLE_ADMIN)) {
            log.error("The configured operator admin exists but cannot log in as an active "
                            + "admin (accepted={}, active={}, admin={}) — not changing it. Fix the "
                            + "user, tenant={}, userId={}",
                    user.getPasswordHash() != null, user.isActive(),
                    user.getRoleNames().contains(SecurityRoles.ROLE_ADMIN), tenant, user.getId());
            return Outcome.CONFLICT;
        }

        if (inviteNeedsReissue(user)) {
            try {
                resendInviteService.resendInvite(user.getId());
            } catch (BusinessException e) {
                if (e.getHttpStatus() != HttpStatus.CONFLICT) {
                    throw e;
                }
                // ResendInviteService's own guards: an invite got queued, or the
                // admin accepted, between our check and its. Benign; the next
                // run sees the new state.
                log.info("Operator admin re-invite skipped, state changed meanwhile ({}), "
                        + "tenant={}, userId={}", e.getMessage(), tenant, user.getId());
                return Outcome.INVITE_IN_PROGRESS;
            }
            log.warn("Operator admin had no usable invite (email permanently failed or token "
                    + "expired) — re-invited, tenant={}, userId={}", tenant, user.getId());
            return Outcome.REINVITED;
        }
        log.warn("Operator admin has not accepted the invite yet — waiting, tenant={}, userId={}",
                tenant, user.getId());
        return Outcome.INVITE_IN_PROGRESS;
    }

    /**
     * An invite still being sent (PENDING, or FAILED with retries left) is left
     * alone; one that permanently failed, or a sent one whose token is no longer
     * valid, is reissued.
     */
    private boolean inviteNeedsReissue(User user) {
        final Optional<AuthEmailOutbox> latest = outboxRepository
                .findFirstByUserIdAndEmailTypeOrderByCreatedAtDesc(user.getId(), AuthEmailType.INVITE);
        if (latest.isPresent()) {
            switch (latest.get().getStatus()) {
                case PENDING, FAILED -> {
                    return false;
                }
                case PERMANENTLY_FAILED -> {
                    return true;
                }
                case SENT -> {
                    // Sent: still usable only while its token is valid.
                }
            }
        }
        return authTokenRepository.findValidByUserIdAndType(
                user.getId(), AuthToken.Type.INVITE, Instant.now()).isEmpty();
    }
}
