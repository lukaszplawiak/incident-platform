package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.UpdateUserRolesRequest;
import com.incidentplatform.auth.dto.UpdateUserStatusRequest;
import com.incidentplatform.auth.dto.UserSummaryDto;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserManagementService {

    private static final Logger log =
            LoggerFactory.getLogger(UserManagementService.class);

    private final UserRepository userRepository;

    private final AuditEventPublisher auditEventPublisher;

    public UserManagementService(UserRepository userRepository,
                                 AuditEventPublisher auditEventPublisher) {
        this.userRepository      = userRepository;
        this.auditEventPublisher = auditEventPublisher;
    }

    /**
     * Replaces all roles of a user within the current tenant.
     *
     * <p>Roles are replaced atomically — not merged. If the caller sends
     * {@code ["ROLE_ADMIN"]}, any existing {@code ROLE_RESPONDER} is removed.
     * This makes the operation idempotent and predictable.
     *
     * @throws ResourceNotFoundException if user does not exist in this tenant
     */
    @Transactional
    public UserSummaryDto updateRoles(UUID userId,
                                      UpdateUserRolesRequest request) {
        final String tenantId = TenantContext.get();
        final User user = findUserInTenant(userId, tenantId);

        user.updateRoles(request.roles(), tenantId);
        userRepository.save(user);

        auditEventPublisher.publishAuth(
                userId, tenantId,
                AuditEventTypes.USER_ROLES_UPDATED,
                "auth-service",
                userId.toString(),
                "User roles updated",
                java.util.Map.of("roles", request.roles()));

        log.info("Roles updated: userId={}, tenant={}, roles={}",
                userId, tenantId, request.roles());

        return UserSummaryDto.from(user);
    }

    /**
     * Activates or deactivates a user within the current tenant.
     *
     * <p>Deactivated users cannot log in — {@link AuthService#login} filters
     * out inactive users before password verification.
     *
     * @throws ResourceNotFoundException if user does not exist in this tenant
     */
    @Transactional
    public UserSummaryDto updateStatus(UUID userId,
                                       UpdateUserStatusRequest request) {
        final String tenantId = TenantContext.get();
        final User user = findUserInTenant(userId, tenantId);

        user.setActive(request.active());
        userRepository.save(user);

        auditEventPublisher.publishAuth(
                userId, tenantId,
                AuditEventTypes.USER_STATUS_UPDATED,
                "auth-service",
                userId.toString(),
                "User status updated",
                java.util.Map.of("active", request.active()));

        log.info("Status updated: userId={}, tenant={}, active={}",
                userId, tenantId, request.active());

        return UserSummaryDto.from(user);
    }

    /**
     * Soft-deletes a user within the current tenant.
     *
     * <p>Sets {@code deleted_at} on the user record. The user becomes invisible
     * to all application queries because every repository method filters
     * {@code AND deleted_at IS NULL}.
     *
     * <p>The record is NOT physically removed — it remains as a historical
     * anchor for audit trail records in other services that reference this UUID.
     *
     * @throws BusinessException         403 if admin tries to delete themselves
     * @throws ResourceNotFoundException if the user does not exist in this tenant
     */
    @Transactional
    public void deleteUser(UUID userId, UserPrincipal principal) {
        final String tenantId = TenantContext.get();

        if (userId.equals(principal.userId())) {
            throw new BusinessException(
                    ErrorCodes.FORBIDDEN,
                    "You cannot delete your own account",
                    HttpStatus.FORBIDDEN);
        }

        final User user = findUserInTenant(userId, tenantId);
        user.softDelete();
        userRepository.save(user);

        auditEventPublisher.publishAuth(
                userId, tenantId,
                AuditEventTypes.USER_DELETED,
                "auth-service",
                principal.userId().toString(),
                "User soft-deleted",
                java.util.Map.of("deletedBy", principal.userId().toString()));

        log.info("User soft-deleted: userId={}, tenant={}, deletedBy={}",
                userId, tenantId, principal.userId());
    }

    // ── private ───────────────────────────────────────────────────────────

    private User findUserInTenant(UUID userId, String tenantId) {
        return userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }
}