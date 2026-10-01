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
import com.incidentplatform.shared.security.SecurityRoles;
import com.incidentplatform.shared.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Makes sure one tenant has an admin who can log in, by inviting a configured
 * address: the goal-checking reconciliation behind {@link OperatorTenantBootstrap}
 * (backlog #0-16, #0-49), on a schedule, and behind the platform operator's
 * "reissue the first admin's invite" for a provisioned tenant
 * ({@code TenantProvisioningService}, backlog #0-80), on demand. Not a bean:
 * each caller builds one for its tenant and admin address.
 *
 * <p>Each run checks the goal, not a step:
 * <ul>
 *   <li>an active admin with a password exists: done;</li>
 *   <li>the tenant has no user: invite the configured address as admin, through
 *       {@link UserService#createUser} (user, invite token and invite email in one
 *       transaction, audited). No password ever sits in configuration: the admin
 *       sets it by accepting the invite;</li>
 *   <li>the configured admin has not accepted, and their latest invite
 *       permanently failed or they hold no valid invite token: re-invite through
 *       {@link ResendInviteService} (invalidates old tokens, audited);</li>
 *   <li>their invite is still being sent, or was sent and is still valid: wait,
 *       so the admin is not sent a new email every run;</li>
 *   <li>the tenant's users do not include the configured address, or the
 *       configured user cannot log in as an active admin: log an ERROR and do
 *       nothing. A second admin is never created and no user is removed; a human
 *       fixes the data.</li>
 * </ul>
 * Every failure is reported as {@link Outcome#FAILED}, never thrown. The run
 * switches {@link TenantContext} to the tenant and restores whatever it was
 * before: nothing for a scheduled run, the operator's tenant when
 * {@code TenantProvisioningService} calls it inside a request (backlog #0-80).
 */
public final class TenantAdminReconciler {

    private static final Logger log = LoggerFactory.getLogger(TenantAdminReconciler.class);

    /** What one reconciliation found or did. */
    public enum Outcome {
        DISABLED,
        ADMIN_ACTIVE,
        INVITED,
        REINVITED,
        INVITE_IN_PROGRESS,
        CONFLICT,
        FAILED
    }

    private final String tenantId;
    private final String adminEmail;
    private final String label;
    private final String fixHint;
    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final AuthEmailOutboxRepository outboxRepository;
    private final UserService userService;
    private final ResendInviteService resendInviteService;

    /**
     * @param label   names the tenant in logs, e.g. "Operator tenant"
     * @param fixHint where a human finds how to fix conflicting data
     */
    public TenantAdminReconciler(String tenantId, String adminEmail, String label, String fixHint,
                          UserRepository userRepository,
                          AuthTokenRepository authTokenRepository,
                          AuthEmailOutboxRepository outboxRepository,
                          UserService userService,
                          ResendInviteService resendInviteService) {
        this.tenantId = tenantId;
        this.adminEmail = adminEmail == null ? "" : adminEmail.trim();
        this.label = label;
        this.fixHint = fixHint;
        this.userRepository = userRepository;
        this.authTokenRepository = authTokenRepository;
        this.outboxRepository = outboxRepository;
        this.userService = userService;
        this.resendInviteService = resendInviteService;
    }

    public boolean enabled() {
        return !adminEmail.isEmpty();
    }

    public String tenantId() {
        return tenantId;
    }

    /** Read-only: whether the tenant has an active admin who has accepted. */
    public boolean adminCanLogIn() {
        return userRepository.existsActiveAcceptedUserWithRole(tenantId, Role.ROLE_ADMIN);
    }

    public Outcome reconcile() {
        if (!enabled()) {
            log.debug("{} bootstrap disabled (no admin email configured)", label);
            return Outcome.DISABLED;
        }
        final String previousTenant = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            return reconcileEnabled();
        } catch (RuntimeException e) {
            log.error("{} admin reconciliation failed, tenant={}", label, tenantId, e);
            return Outcome.FAILED;
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    private Outcome reconcileEnabled() {
        if (adminCanLogIn()) {
            log.debug("{} has an active admin — nothing to do, tenant={}", label, tenantId);
            return Outcome.ADMIN_ACTIVE;
        }
        final Optional<User> configured = userRepository.findByEmailAndTenantId(adminEmail, tenantId);
        if (configured.isEmpty()) {
            if (userRepository.existsByTenantId(tenantId)) {
                log.error("{} has no admin who can log in, and its users do not include the "
                                + "configured admin email — not creating a second admin. Fix the "
                                + "users of tenant={} ({})", label, tenantId, fixHint);
                return Outcome.CONFLICT;
            }
            userService.createUser(new CreateUserRequest(
                    adminEmail, List.of(SecurityRoles.ROLE_ADMIN)));
            log.info("{} bootstrapped: invite queued for the first admin, tenant={}",
                    label, tenantId);
            return Outcome.INVITED;
        }
        final User user = configured.get();
        if (user.getPasswordHash() != null || !user.isActive()
                || !user.getRoleNames().contains(SecurityRoles.ROLE_ADMIN)) {
            log.error("The configured admin of {} exists but cannot log in as an active admin "
                            + "(accepted={}, active={}, admin={}) — not changing it. Fix the user, "
                            + "tenant={}, userId={} ({})",
                    label, user.getPasswordHash() != null, user.isActive(),
                    user.getRoleNames().contains(SecurityRoles.ROLE_ADMIN), tenantId, user.getId(),
                    fixHint);
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
                log.info("{} admin re-invite skipped, state changed meanwhile ({}), "
                        + "tenant={}, userId={}", label, e.getMessage(), tenantId, user.getId());
                return Outcome.INVITE_IN_PROGRESS;
            }
            log.warn("{} admin had no usable invite (email permanently failed or token "
                    + "expired) — re-invited, tenant={}, userId={}", label, tenantId, user.getId());
            return Outcome.REINVITED;
        }
        log.warn("{} admin has not accepted the invite yet — waiting, tenant={}, userId={}",
                label, tenantId, user.getId());
        return Outcome.INVITE_IN_PROGRESS;
    }

    /**
     * An invite still being sent (PENDING, or FAILED and still being retried
     * until its deadline, backlog #0-52) is left alone; one that permanently
     * failed, or a sent or superseded one with no valid invite token left, is
     * reissued.
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
                case SENT, SUPERSEDED -> {
                    // Sent: still usable only while its token is valid.
                    // Superseded (backlog #0-52): the scheduler found its token
                    // used or invalidated; whether a valid one exists decides.
                }
            }
        }
        return authTokenRepository.findValidByUserIdAndType(
                user.getId(), AuthToken.Type.INVITE, Instant.now()).isEmpty();
    }
}
