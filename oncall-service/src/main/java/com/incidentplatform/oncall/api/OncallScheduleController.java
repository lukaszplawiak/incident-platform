package com.incidentplatform.oncall.api;

import com.incidentplatform.oncall.dto.CreateOncallScheduleRequest;
import com.incidentplatform.oncall.dto.CurrentOncallResponse;
import com.incidentplatform.oncall.dto.OncallScheduleDto;
import com.incidentplatform.oncall.dto.SlackUserLookupResponse;
import com.incidentplatform.oncall.service.OncallScheduleService;
import com.incidentplatform.shared.dto.PagedResponse;
import com.incidentplatform.shared.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/oncall")
@Tag(name = "On-call Schedules",
        description = "On-call schedule management and current on-call lookup")
@SecurityRequirement(name = "Bearer Authentication")
public class OncallScheduleController {

    private static final Logger log =
            LoggerFactory.getLogger(OncallScheduleController.class);

    private final OncallScheduleService service;

    public OncallScheduleController(OncallScheduleService service) {
        this.service = service;
    }

    /**
     * No {@code @PreAuthorize} — this endpoint is called by
     * {@code notification-service} and {@code escalation-service} using a
     * service-to-service token ({@code ROLE_SERVICE}). It is already
     * restricted at the {@link com.incidentplatform.oncall.config.SecurityConfig}
     * URL level to {@code hasAnyRole(SERVICE, ADMIN)}.
     * Method-level duplication would add noise without extra safety here
     * since the URL-level rule is already more restrictive than "any
     * authenticated user".
     */
    @GetMapping("/current")
    @Operation(summary = "Get current on-call schedule",
            description = "Returns the currently active on-call person for the " +
                    "given role. Called by internal services (notification, escalation). " +
                    "Access restricted to ROLE_SERVICE and ROLE_ADMIN at URL level.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current on-call details"),
            @ApiResponse(responseCode = "204", description = "No on-call configured for this role/tenant")
    })
    public ResponseEntity<?> getCurrentOncall(
            @RequestParam(required = false) String role) {
        final String tenantId = TenantContext.get();

        if (role != null && !role.isBlank()) {
            log.debug("GET /api/v1/oncall/current?role={}, tenant={}",
                    role, tenantId);
            return service.getCurrentOncall(tenantId, role)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.noContent().build());
        }

        log.debug("GET /api/v1/oncall/current, tenant={}", tenantId);
        final List<CurrentOncallResponse> current =
                service.getAllCurrentOncall(tenantId);

        if (current.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(current);
    }

    @GetMapping(value = "/schedules", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('RESPONDER') or hasRole('ADMIN')")
    @Operation(summary = "List on-call schedules (paginated)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated list of schedules"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT token"),
            @ApiResponse(responseCode = "403",
                    description = "Insufficient permissions — ROLE_RESPONDER or ROLE_ADMIN required")
    })
    public ResponseEntity<PagedResponse<OncallScheduleDto>> getSchedules(
            @PageableDefault(size = 20, sort = "startsAt") Pageable pageable) {
        final String tenantId = TenantContext.get();
        log.debug("GET /api/v1/oncall/schedules, tenant={}, page={}",
                tenantId, pageable.getPageNumber());
        final Page<OncallScheduleDto> page =
                service.getSchedules(tenantId, pageable);
        return ResponseEntity.ok(PagedResponse.of(page));
    }

    @GetMapping(value = "/schedules/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('RESPONDER') or hasRole('ADMIN')")
    @Operation(summary = "Get on-call schedule by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Schedule details"),
            @ApiResponse(responseCode = "404", description = "Schedule not found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT token"),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions")
    })
    public ResponseEntity<OncallScheduleDto> getById(
            @PathVariable UUID id) {
        final String tenantId = TenantContext.get();
        log.debug("GET /api/v1/oncall/schedules/{}, tenant={}", id, tenantId);
        return ResponseEntity.ok(service.getById(id, tenantId));
    }

    @PostMapping(
            value = "/schedules",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a new on-call schedule entry")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Schedule created"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "409",
                    description = "Schedule overlaps with an existing entry"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT token"),
            @ApiResponse(responseCode = "403",
                    description = "Insufficient permissions — ROLE_ADMIN required")
    })
    public ResponseEntity<OncallScheduleDto> create(
            @Valid @RequestBody CreateOncallScheduleRequest request) {
        final String tenantId = TenantContext.get();
        log.debug("POST /api/v1/oncall/schedules, tenant={}, role={}",
                tenantId, request.role());

        final OncallScheduleDto created = service.create(tenantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/schedules/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete an on-call schedule entry")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Schedule deleted"),
            @ApiResponse(responseCode = "404", description = "Schedule not found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT token"),
            @ApiResponse(responseCode = "403",
                    description = "Insufficient permissions — ROLE_ADMIN required")
    })
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        final String tenantId = TenantContext.get();
        log.debug("DELETE /api/v1/oncall/schedules/{}, tenant={}", id, tenantId);
        service.delete(id, tenantId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Looks up the on-call schedule entry for a given Slack user ID within
     * the calling tenant. Called by {@code notification-service} when
     * resolving a Slack ACK button click to the internal system user ID.
     *
     * <p>No {@code @PreAuthorize} — this endpoint is called by
     * {@code notification-service} using a service token ({@code ROLE_SERVICE}).
     * {@code ROLE_SERVICE} is not granted {@code ROLE_RESPONDER} or
     * {@code ROLE_ADMIN}, so adding {@code @PreAuthorize("hasRole('ROLE_RESPONDER')")}
     * would break the inter-service call. The endpoint is protected by
     * {@code anyRequest().authenticated()} at the URL level — only a valid
     * JWT (service or user token) can reach it.
     *
     * <p>Returns 204 No Content when no matching schedule is found.
     */
    @GetMapping("/by-slack/{slackUserId}")
    @Operation(summary = "Find on-call schedule by Slack user ID",
            description = "Called by notification-service to resolve Slack ACK " +
                    "button clicks to internal system user IDs. " +
                    "Requires a valid service or user JWT.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User found in on-call schedule"),
            @ApiResponse(responseCode = "204", description = "No matching schedule found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT token")
    })
    public ResponseEntity<SlackUserLookupResponse> findBySlackUserId(
            @PathVariable String slackUserId) {
        final String tenantId = TenantContext.get();
        log.debug("GET /api/v1/oncall/by-slack/{}, tenant={}",
                slackUserId, tenantId);
        return service.findBySlackUserId(tenantId, slackUserId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }
}