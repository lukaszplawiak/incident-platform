package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.User;
import com.incidentplatform.auth.dto.UpdateUserRolesRequest;
import com.incidentplatform.auth.dto.UpdateUserStatusRequest;
import com.incidentplatform.auth.dto.UserSummaryDto;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
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

    public UserManagementService(UserRepository userRepository) {
        this.userRepository = userRepository;
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

        log.info("Status updated: userId={}, tenant={}, active={}",
                userId, tenantId, request.active());

        return UserSummaryDto.from(user);
    }

    // ── private ───────────────────────────────────────────────────────────

    private User findUserInTenant(UUID userId, String tenantId) {
        return userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }
}