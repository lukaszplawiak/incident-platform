package com.incidentplatform.auth.service;

import com.incidentplatform.auth.dto.UserSummaryDto;
import com.incidentplatform.auth.repository.UserRepository;
import com.incidentplatform.shared.dto.PagedResponse;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserQueryService {

    private static final Logger log =
            LoggerFactory.getLogger(UserQueryService.class);

    private final UserRepository userRepository;

    public UserQueryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Lists all users in the current tenant — paginated.
     * Restricted to ROLE_ADMIN (enforced by @PreAuthorize on the controller).
     */
    @Transactional(readOnly = true)
    public PagedResponse<UserSummaryDto> listUsers(Pageable pageable) {
        final String tenantId = TenantContext.get();

        log.debug("Listing users: tenant={}, page={}", tenantId,
                pageable.getPageNumber());

        return PagedResponse.of(
                userRepository.findByTenantIdAndDeletedAtIsNull(tenantId, pageable)
                        .map(UserSummaryDto::from));
    }

    /**
     * Returns the authenticated user's own profile.
     * Any authenticated user can call this — no role restriction.
     */
    @Transactional(readOnly = true)
    public UserSummaryDto getMe(UserPrincipal principal) {
        final String tenantId = TenantContext.get();

        log.debug("GET /me: userId={}, tenant={}", principal.userId(), tenantId);

        return userRepository
                .findByIdAndTenantIdAndDeletedAtIsNull(principal.userId(), tenantId)
                .map(UserSummaryDto::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User", principal.userId()));
    }
}