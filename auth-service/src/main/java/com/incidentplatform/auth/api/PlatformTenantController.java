package com.incidentplatform.auth.api;

import com.incidentplatform.auth.dto.ProvisionTenantRequest;
import com.incidentplatform.auth.dto.ProvisionTenantResponse;
import com.incidentplatform.auth.dto.TenantDto;
import com.incidentplatform.auth.ratelimit.PlatformRateLimiter;
import com.incidentplatform.auth.service.TenantProvisioningService;
import com.incidentplatform.shared.dto.PagedResponse;
import com.incidentplatform.shared.security.UserPrincipal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * The platform API: how a platform operator onboards customers (backlog #0-80).
 * Only an admin of the platform-operator tenant, logged in with a JWT, gets in
 * ({@code PlatformAccess}, enforced in the filter chain and on every method).
 * The rules and why this crosses tenant boundaries at all:
 * {@link TenantProvisioningService}; operator guide: docs/tenant-provisioning.md.
 *
 * <p>Since backlog #0-83 the write operations are also limited per operator and in total
 * ({@link PlatformRateLimiter}: 429 when the limit is reached, 503 while it
 * cannot be checked). The limit is taken after bean validation of the body
 * (a malformed request is refused with 400 without counting) and before the
 * service runs, so an attempt the service refuses (400, 404, 409) counts: it
 * bounds probing as well, and a retrying script stops instead of hammering. Every
 * tenant created is counted
 * ({@code platform.tenants.provisioned}) for the provisioning-spike alert.
 */
@RestController
@RequestMapping("/api/v1/platform/tenants")
@Tag(name = "Platform — tenants",
        description = "Tenant provisioning for platform operators (backlog #0-80)")
@SecurityRequirement(name = "Bearer Authentication")
public class PlatformTenantController {

    private final TenantProvisioningService provisioningService;
    private final PlatformRateLimiter rateLimiter;
    private final Counter provisioned;

    public PlatformTenantController(TenantProvisioningService provisioningService,
                                    PlatformRateLimiter rateLimiter,
                                    MeterRegistry meterRegistry) {
        this.provisioningService = provisioningService;
        this.rateLimiter = rateLimiter;
        this.provisioned = Counter.builder("platform.tenants.provisioned")
                .description("Tenants created through the platform API (backlog #0-80, #0-83)")
                .register(meterRegistry);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@platformAccess.isPlatformAdmin(authentication)")
    @Operation(summary = "Create a tenant and invite its first admin",
            description = """
                    Creates the tenant and, in the same transaction, its first admin
                    without a password; the admin gets an invite email and sets their
                    own password with POST /api/v1/auth/accept-invite.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Tenant created, first admin invited"),
            @ApiResponse(responseCode = "400", description = "Invalid or reserved tenant id, invalid input"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Not an admin of the platform-operator tenant, an API key, or a session without a recent MFA login"),
            @ApiResponse(responseCode = "409", description = "A tenant with this id already exists"),
            @ApiResponse(responseCode = "429", description = "Per-operator limit reached; see Retry-After"),
            @ApiResponse(responseCode = "503", description = "The limit cannot be checked now; see Retry-After")
    })
    public ResponseEntity<ProvisionTenantResponse> provision(
            @Valid @RequestBody ProvisionTenantRequest request,
            @AuthenticationPrincipal UserPrincipal operator) {
        final PlatformRateLimiter.Decision limit = rateLimiter.tryConsume(operator.userId());
        if (!limit.allowed()) {
            return refused(limit);
        }
        // The service's transaction has committed when it returns, so the
        // counter counts tenants that exist.
        final ProvisionTenantResponse response = provisioningService.provision(request, operator);
        provisioned.increment();
        final URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{tenantId}").buildAndExpand(response.tenantId()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping(value = "/{tenantId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@platformAccess.isPlatformAdmin(authentication)")
    @Operation(summary = "Show one tenant (metadata only)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant returned"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Not an admin of the platform-operator tenant, an API key, or a session without a recent MFA login"),
            @ApiResponse(responseCode = "404", description = "No such tenant")
    })
    public ResponseEntity<TenantDto> get(@PathVariable String tenantId) {
        return ResponseEntity.ok(provisioningService.get(tenantId));
    }

    @PostMapping("/{tenantId}/admin-invite")
    @PreAuthorize("@platformAccess.isPlatformAdmin(authentication)")
    @Operation(summary = "Reissue the first admin's invite",
            description = """
                    For a tenant whose first admin has not accepted, when the invite
                    email permanently failed or the invite expired (7 days). Refused
                    while the invite is still on its way or valid, and once the tenant
                    has an active admin.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Invite reissued"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Not an admin of the platform-operator tenant, an API key, or a session without a recent MFA login"),
            @ApiResponse(responseCode = "404", description = "No such tenant"),
            @ApiResponse(responseCode = "409", description = "Nothing to reissue, or the tenant's users need fixing by hand"),
            @ApiResponse(responseCode = "429", description = "Per-operator limit reached; see Retry-After"),
            @ApiResponse(responseCode = "503", description = "The limit cannot be checked now; see Retry-After")
    })
    public ResponseEntity<Void> reissueFirstAdminInvite(
            @PathVariable String tenantId,
            @AuthenticationPrincipal UserPrincipal operator) {
        final PlatformRateLimiter.Decision limit = rateLimiter.tryConsume(operator.userId());
        if (!limit.allowed()) {
            return refused(limit);
        }
        provisioningService.reissueFirstAdminInvite(tenantId, operator);
        return ResponseEntity.accepted().build();
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@platformAccess.isPlatformAdmin(authentication)")
    @Operation(summary = "List tenants (metadata only, newest first)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tenant list returned"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Not an admin of the platform-operator tenant, an API key, or a session without a recent MFA login")
    })
    public ResponseEntity<PagedResponse<TenantDto>> list(@PageableDefault(size = 20) Pageable pageable) {
        // The order is fixed (newest first); a client-supplied sort is ignored.
        return ResponseEntity.ok(PagedResponse.of(provisioningService.list(
                PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), 100)))));
    }

    /** 429 or 503 with Retry-After and no body, as ingestion-service's limiter answers. */
    private static <T> ResponseEntity<T> refused(PlatformRateLimiter.Decision limit) {
        final HttpStatus status = limit.outcome() == PlatformRateLimiter.Outcome.LIMITED
                ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(limit.retryAfterSeconds()))
                .build();
    }
}
