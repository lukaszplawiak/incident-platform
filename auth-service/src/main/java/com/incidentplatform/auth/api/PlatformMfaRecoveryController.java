package com.incidentplatform.auth.api;

import com.incidentplatform.auth.domain.MfaRecoveryRequest;
import com.incidentplatform.auth.dto.MfaRecoveryRequestDto;
import com.incidentplatform.auth.dto.RequestMfaRecoveryRequest;
import com.incidentplatform.auth.ratelimit.PlatformRateLimiter;
import com.incidentplatform.auth.ratelimit.RateLimitDecision;
import com.incidentplatform.auth.service.MfaRecoveryService;
import com.incidentplatform.shared.dto.PagedResponse;
import com.incidentplatform.shared.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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

import java.util.UUID;

/**
 * The platform API's MFA recovery of a customer tenant's only admin (backlog
 * #0-90): an operator asks, the account is told and can cancel, the reset runs
 * after the waiting period. The rules: {@link MfaRecoveryService}; operator
 * guide: docs/tenant-provisioning.md.
 *
 * <p>Under {@code /api/v1/platform/**}, so {@code PlatformAccess} applies in
 * the filter chain as well as on every method. A request counts against the
 * platform API's write limits ({@link PlatformRateLimiter}, taken after bean
 * validation and before the service, as {@code PlatformTenantController}
 * does); listing and cancelling do not (cancelling is the safe direction).
 */
@RestController
@RequestMapping("/api/v1/platform")
@Tag(name = "Platform — MFA recovery",
        description = "Recovery of a customer tenant's only admin locked out of MFA (backlog #0-90)")
@SecurityRequirement(name = "Bearer Authentication")
public class PlatformMfaRecoveryController {

    private final MfaRecoveryService recoveryService;
    private final PlatformRateLimiter rateLimiter;

    public PlatformMfaRecoveryController(MfaRecoveryService recoveryService,
                                         PlatformRateLimiter rateLimiter) {
        this.recoveryService = recoveryService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping(value = "/tenants/{tenantId}/mfa-recovery",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@platformAccess.isPlatformAdmin(authentication)")
    @Operation(summary = "Ask to reset the MFA of a customer tenant's only admin",
            description = """
                    For an active admin with MFA who is the tenant's only active admin,
                    after verifying the person outside the account (its password and
                    mailbox may be someone else's). Nothing changes now: the account is
                    emailed, and the factor, password and sessions are reset once the
                    waiting period (default 72 h) has passed since that email was sent,
                    unless the account or an operator cancels.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Request recorded, the account is being told"),
            @ApiResponse(responseCode = "400", description = "Invalid input, or a reserved tenant"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Not an admin of the platform-operator tenant, an API key, or a session without a recent MFA login"),
            @ApiResponse(responseCode = "404", description = "No such tenant or user"),
            @ApiResponse(responseCode = "409", description = "Not an active admin with MFA, the tenant has another active admin, or a request is already open"),
            @ApiResponse(responseCode = "429", description = "Per-operator or platform limit reached; see Retry-After"),
            @ApiResponse(responseCode = "503", description = "The limit cannot be checked now; see Retry-After")
    })
    public ResponseEntity<MfaRecoveryRequestDto> request(
            @PathVariable String tenantId,
            @Valid @RequestBody RequestMfaRecoveryRequest body,
            @AuthenticationPrincipal UserPrincipal operator) {
        final RateLimitDecision limit = rateLimiter.tryConsume(operator.userId());
        if (!limit.allowed()) {
            return RateLimitResponses.refused(limit);
        }
        final MfaRecoveryRequest request = recoveryService.request(tenantId, body.userId(),
                body.verificationMethod(), body.verificationNote(), operator);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(MfaRecoveryRequestDto.from(request, recoveryService.waitingPeriod()));
    }

    @GetMapping(value = "/tenants/{tenantId}/mfa-recovery", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@platformAccess.isPlatformAdmin(authentication)")
    @Operation(summary = "List a tenant's MFA recovery requests (newest first)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Requests returned"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Not an admin of the platform-operator tenant, an API key, or a session without a recent MFA login")
    })
    public ResponseEntity<PagedResponse<MfaRecoveryRequestDto>> list(
            @PathVariable String tenantId, @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(recoveryService.list(tenantId,
                        PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), 100)))
                .map(request -> MfaRecoveryRequestDto.from(request, recoveryService.waitingPeriod()))));
    }

    @PostMapping("/mfa-recovery/{requestId}/cancel")
    @PreAuthorize("@platformAccess.isPlatformAdmin(authentication)")
    @Operation(summary = "Cancel an open MFA recovery request")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Cancelled"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Not an admin of the platform-operator tenant, an API key, or a session without a recent MFA login"),
            @ApiResponse(responseCode = "404", description = "No such request"),
            @ApiResponse(responseCode = "409", description = "The request has already ended")
    })
    public ResponseEntity<Void> cancel(@PathVariable UUID requestId,
                                       @AuthenticationPrincipal UserPrincipal operator) {
        recoveryService.cancelByOperator(requestId, operator);
        return ResponseEntity.noContent().build();
    }
}
