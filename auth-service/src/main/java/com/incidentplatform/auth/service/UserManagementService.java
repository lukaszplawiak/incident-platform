package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.Role;
import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.UpdateUserRolesRequest;
import com.incidentplatform.auth.dto.UpdateUserStatusRequest;
import com.incidentplatform.auth.dto.UserSummaryDto;
import com.incidentplatform.auth.repository.TeamMemberRepository;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.auth.service.ApiKeyService;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class UserManagementService {

    private static final Logger log =
            LoggerFactory.getLogger(UserManagementService.class);

    private final UserRepository userRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final AuditEventPublisher auditEventPublisher;
    private final ApiKeyService apiKeyService;

    public UserManagementService(UserRepository userRepository,
                                 TeamMemberRepository teamMemberRepository,
                                 AuditEventPublisher auditEventPublisher,
                                 ApiKeyService apiKeyService) {
        this.userRepository       = userRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.auditEventPublisher  = auditEventPublisher;
        this.apiKeyService        = apiKeyService;
    }

    // ── updateRoles ───────────────────────────────────────────────────────

    @Transactional
    public UserSummaryDto updateRoles(UUID userId, UpdateUserRolesRequest request) {
        final String tenantId = TenantContext.get();
        final User user = requireActiveUser(userId, tenantId);

        final boolean willStillBeAdmin = request.roles().contains(Role.ROLE_ADMIN.name());
        ensureNotRemovingLastAdmin(user, tenantId, willStillBeAdmin);

        user.updateRoles(request.roles(), tenantId);
        userRepository.save(user);

        auditEventPublisher.publishAuth(
                userId, tenantId,
                AuditEventTypes.USER_ROLES_UPDATED,
                "auth-service",
                userId.toString(),
                "User roles updated",
                Map.of("roles", request.roles()));

        log.info("Roles updated: userId={}, tenant={}, roles={}",
                userId, tenantId, request.roles());

        return UserSummaryDto.from(user);
    }

    // ── updateStatus ──────────────────────────────────────────────────────

    @Transactional
    public UserSummaryDto updateStatus(UUID userId, UpdateUserStatusRequest request) {
        final String tenantId = TenantContext.get();
        final User user = requireActiveUser(userId, tenantId);

        final boolean willStillBeActiveAdmin =
                request.active() && user.getRoleNames().contains(Role.ROLE_ADMIN.name());
        ensureNotRemovingLastAdmin(user, tenantId, willStillBeActiveAdmin);

        user.setActive(request.active());
        userRepository.save(user);

        auditEventPublisher.publishAuth(
                userId, tenantId,
                AuditEventTypes.USER_STATUS_UPDATED,
                "auth-service",
                userId.toString(),
                "User status updated",
                Map.of("active", request.active()));

        log.info("Status updated: userId={}, tenant={}, active={}",
                userId, tenantId, request.active());

        return UserSummaryDto.from(user);
    }

    // ── archiveUser ───────────────────────────────────────────────────────

    /**
     * Archives a user — hides from normal queries but preserves the record.
     *
     * <p>Replaces the former {@code deleteUser()} / soft-delete approach.
     * The user can be restored via {@link #restoreUser} or permanently
     * anonymized for GDPR via {@link #anonymizeUser}.
     *
     * @throws BusinessException         403 if admin tries to archive themselves
     * @throws BusinessException         409 if archiving would leave the tenant
     *                                   with zero active administrators — see
     *                                   {@link #ensureNotRemovingLastAdmin}
     * @throws ResourceNotFoundException if the user does not exist or is already
     *                                   archived/anonymized
     */
    @Transactional
    public void archiveUser(UUID userId, UserPrincipal principal) {
        final String tenantId = TenantContext.get();

        if (userId.equals(principal.userId())) {
            throw new BusinessException(
                    ErrorCodes.FORBIDDEN,
                    "You cannot archive your own account",
                    HttpStatus.FORBIDDEN);
        }

        final User user = requireActiveUser(userId, tenantId);

        // Archiving always removes active status — never "still active" after.
        ensureNotRemovingLastAdmin(user, tenantId, false);

        user.archive();
        userRepository.save(user);

        // Revoke all personal API keys — archived user cannot authenticate
        apiKeyService.revokeAllPersonalKeysForUser(userId, tenantId);

        auditEventPublisher.publishAuth(
                userId, tenantId,
                AuditEventTypes.USER_ARCHIVED,
                "auth-service",
                principal.userId().toString(),
                "User archived",
                Map.of("archivedBy", principal.userId().toString()));

        log.info("User archived: userId={}, tenant={}, by={}",
                userId, tenantId, principal.userId());
    }

    // ── restoreUser ───────────────────────────────────────────────────────

    /**
     * Restores an archived user to active state.
     *
     * @throws BusinessException         404 if user not found or not archived
     * @throws BusinessException         409 if user is anonymized (irreversible)
     */
    @Transactional
    public void restoreUser(UUID userId, UserPrincipal principal) {
        final String tenantId = TenantContext.get();

        final User user = userRepository
                .findAnyByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        if (user.isAnonymized()) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "Cannot restore anonymized user — personal data has been erased",
                    HttpStatus.CONFLICT);
        }

        if (!user.isArchived()) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "User is not archived",
                    HttpStatus.CONFLICT);
        }

        user.restore();
        userRepository.save(user);

        auditEventPublisher.publishAuth(
                userId, tenantId,
                AuditEventTypes.USER_RESTORED,
                "auth-service",
                principal.userId().toString(),
                "User restored",
                Map.of("restoredBy", principal.userId().toString()));

        log.info("User restored: userId={}, tenant={}, by={}",
                userId, tenantId, principal.userId());
    }

    // ── anonymizeUser ─────────────────────────────────────────────────────

    /**
     * Anonymizes a user's personal data for GDPR compliance.
     *
     * <p>This operation is <strong>irreversible</strong>. It:
     * <ul>
     *   <li>Replaces email with {@code "anonymized-{uuid}@deleted.invalid"}</li>
     *   <li>Nulls password_hash</li>
     *   <li>Removes all roles</li>
     *   <li>Removes all team memberships</li>
     * </ul>
     *
     * <p>The user UUID is preserved — historical references (audit logs,
     * incident assignments) remain valid. The UUID itself is not PII.
     *
     * <h3>Audit log note</h3>
     * Audit log entries referencing this userId by UUID are not modified —
     * a bare UUID without accompanying email/name is not personal data
     * under GDPR (no re-identification possible without the PII that was erased).
     *
     * <h3>Data Vault TODO</h3>
     * This in-place anonymization approach has residual risk: if any other
     * system has cached the email, it may still be linkable to this UUID.
     * The Data Vault pattern (separate personal_data table, DELETE on erasure)
     * eliminates this risk entirely and is the recommended long-term approach.
     *
     * @throws BusinessException 409 if user is not archived (must archive first)
     * @throws BusinessException 409 if user is already anonymized
     */
    @Transactional
    public void anonymizeUser(UUID userId, UserPrincipal principal) {
        final String tenantId = TenantContext.get();

        final User user = userRepository
                .findAnyByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        if (user.isAnonymized()) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "User is already anonymized",
                    HttpStatus.CONFLICT);
        }

        if (!user.isArchived()) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "User must be archived before anonymization. " +
                            "Call DELETE /api/v1/users/{id} first.",
                    HttpStatus.CONFLICT);
        }

        // Remove team memberships before anonymizing
        teamMemberRepository.deleteByUserId(userId);

        // Revoke all personal API keys — anonymized user cannot authenticate
        apiKeyService.revokeAllPersonalKeysForUser(userId, tenantId);

        // Generate a stable anonymous ID for the email alias
        final UUID anonymousId = UUID.randomUUID();
        user.anonymize(anonymousId);
        userRepository.save(user);

        // Note: we do NOT include userId in audit metadata — the point of
        // anonymization is that we cannot link back to the person.
        // The audit record confirms anonymization happened, by whom, when.
        auditEventPublisher.publishAuth(
                userId, tenantId,
                AuditEventTypes.USER_ANONYMIZED,
                "auth-service",
                principal.userId().toString(),
                "User anonymized (GDPR erasure)",
                Map.of("anonymizedBy", principal.userId().toString()));

        log.info("User anonymized: userId={}, tenant={}, by={}",
                userId, tenantId, principal.userId());
    }

    // ── private ───────────────────────────────────────────────────────────

    /**
     * Guards against a tenant ending up with zero active administrators —
     * a real, previously unprotected risk: {@code updateRoles} and
     * {@code updateStatus} had no self-check at all (a sole admin could
     * demote or deactivate themselves, or another admin, with nothing
     * stopping it), and {@code archiveUser}'s existing self-archive block
     * only covered the self-archive path specifically, not "archive the
     * only other admin" or the roles/status paths.
     *
     * <p>Deliberately not a new "Account Owner" role — that would solve a
     * bigger problem (billing ownership, account transfer) this platform
     * doesn't have yet (no self-service multi-tenant billing model). This
     * is the narrower, surgical fix for the concrete risk that actually
     * exists today: {@code ROLE_ADMIN} reaching zero active holders in a
     * tenant, which would require manual database intervention to recover
     * from — no in-app path exists to grant ROLE_ADMIN back once nobody
     * holds it.
     *
     * @param user                 the user the operation targets
     * @param tenantId             the tenant to check other admins within
     * @param willStillBeActiveAdmin whether, after the operation being
     *                             guarded completes, this user will still
     *                             be both {@code active} and hold
     *                             {@code ROLE_ADMIN}. If {@code true}, the
     *                             operation isn't removing admin status
     *                             from anyone and this method does nothing.
     * @throws BusinessException 409 if {@code user} is currently an active
     *                           administrator, the operation would change
     *                           that, and no other active administrator
     *                           exists in the tenant
     */
    private void ensureNotRemovingLastAdmin(User user, String tenantId,
                                            boolean willStillBeActiveAdmin) {
        final boolean isCurrentlyActiveAdmin =
                user.isActive() && user.getRoleNames().contains(Role.ROLE_ADMIN.name());

        if (!isCurrentlyActiveAdmin || willStillBeActiveAdmin) {
            // Either this user isn't an active admin to begin with (nothing
            // to protect), or they'll remain one after this operation
            // (nothing being removed) — no need to check further.
            return;
        }

        final long otherActiveAdmins = userRepository.countActiveUsersWithRoleExcluding(
                tenantId, Role.ROLE_ADMIN, user.getId());

        if (otherActiveAdmins == 0) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "Cannot remove the last active administrator from this " +
                            "tenant. Promote another user to ROLE_ADMIN first.",
                    HttpStatus.CONFLICT);
        }
    }

    /**
     * Finds an active (non-archived, non-anonymized) user.
     * @SQLRestriction on User ensures archived/anonymized users are invisible
     * to normal repository queries — this just wraps the 404 handling.
     */
    private User requireActiveUser(UUID userId, String tenantId) {
        return userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }
}