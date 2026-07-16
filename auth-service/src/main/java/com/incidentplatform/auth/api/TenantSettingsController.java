package com.incidentplatform.auth.api;

import com.incidentplatform.auth.dto.TenantSettingsDto;
import com.incidentplatform.auth.dto.UpdateTenantSettingsRequest;
import com.incidentplatform.auth.service.TenantSettingsService;
import com.incidentplatform.shared.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/settings")
@Tag(name = "Tenant Settings",
        description = "Per-tenant configuration. ADMIN only.")
public class TenantSettingsController {

    private final TenantSettingsService tenantSettingsService;

    public TenantSettingsController(TenantSettingsService tenantSettingsService) {
        this.tenantSettingsService = tenantSettingsService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get tenant settings")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Settings returned")
    })
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TenantSettingsDto> getSettings() {
        return ResponseEntity.ok(tenantSettingsService.getSettings());
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update tenant settings",
            description = """
                       Updates tenant-wide settings. ADMIN only.

                       mfaRequired=true: all users in this tenant must configure MFA
                       before accessing the system. Users without MFA receive
                       MFA_SETUP_REQUIRED error at login and are redirected to
                       POST /api/v1/auth/mfa/setup.
                       """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Settings updated")
    })
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TenantSettingsDto> updateSettings(
            @Valid @RequestBody UpdateTenantSettingsRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(
                tenantSettingsService.updateSettings(request.mfaRequired(), principal));
    }
}