package com.incidentplatform.auth.api;

import com.incidentplatform.auth.service.TenantAccessService;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.TenantAccessState;
import com.incidentplatform.shared.security.TenantStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What a tenant may do, for the other services (backlog #0-82, step 2): read by
 * {@code AuthServiceTenantStatusProvider} in {@code shared}, which every service
 * but this one uses to refuse a suspended tenant's requests.
 *
 * <p>auth-service's second {@code ROLE_SERVICE} endpoint, after the Slack
 * workspace read (backlog #0-21/#0-30), and built the same way: a service token
 * with {@code aud=auth-service} only, and the tenant is the token's signed
 * claim — a service asks about the tenant it acts for, never another. It
 * answers the {@link com.incidentplatform.shared.security.TenantAccess} and,
 * for a suspended tenant, when it was suspended (step 2b: a pause of
 * background work is measured from it); the status behind it (mode, reason,
 * the operator's note) stays here.
 */
@RestController
public class InternalTenantStatusController {

    private final TenantAccessService tenantAccessService;

    public InternalTenantStatusController(TenantAccessService tenantAccessService) {
        this.tenantAccessService = tenantAccessService;
    }

    @GetMapping(value = "/api/v1/internal/tenant-status", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('SERVICE')")
    @Operation(
            summary = "[Internal] What this tenant may do: FULL, READ_ONLY or NONE",
            description = "Service-to-service only (backlog #0-82). Called by every other service to "
                    + "refuse a suspended tenant's requests.",
            hidden = true)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The tenant's access"),
            @ApiResponse(responseCode = "403", description = "ROLE_SERVICE with aud=auth-service required")
    })
    public TenantStatusResponse getForService() {
        final TenantAccessState state = tenantAccessService.stateOf(TenantContext.get());
        return new TenantStatusResponse(state.access(), state.since());
    }
}
