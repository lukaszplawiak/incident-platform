package com.incidentplatform.incident.api;

import com.incidentplatform.incident.dto.*;
import com.incidentplatform.shared.dto.PagedResponse;
import com.incidentplatform.incident.domain.IncidentStatus;
import com.incidentplatform.incident.service.IncidentCommandService;
import com.incidentplatform.incident.service.IncidentQueryService;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.SourceType;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/incidents")
@Tag(name = "Incidents",
        description = "Incident lifecycle management")
@SecurityRequirement(name = "Bearer Authentication")
public class IncidentController {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentController.class);

    private final IncidentQueryService queryService;
    private final IncidentCommandService commandService;

    public IncidentController(IncidentQueryService queryService,
                              IncidentCommandService commandService) {
        this.queryService = queryService;
        this.commandService = commandService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('RESPONDER') or hasRole('ADMIN')")
    @Operation(summary = "List incidents with optional filtering and pagination")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paginated list of incidents"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ResponseEntity<PagedResponse<IncidentDto>> listIncidents(

            @Parameter(description = "Filter by status")
            @RequestParam(required = false) IncidentStatus status,

            @Parameter(description = "Filter by severity: CRITICAL, HIGH, MEDIUM, LOW")
            @RequestParam(required = false) Severity severity,

            @Parameter(description = "Filter by source type: OPS, SECURITY")
            @RequestParam(required = false) SourceType sourceType,

            @Parameter(description = "Filter by source name: prometheus, wazuh, generic")
            @RequestParam(required = false) String source,

            @Parameter(description = "Filter by team UUID — shows only incidents assigned to this team")
            @RequestParam(required = false) UUID teamId,

            @PageableDefault(size = 20, sort = "createdAt")
            Pageable pageable) {

        final String tenantId = TenantContext.get();
        final IncidentFilter filter = new IncidentFilter(
                status, severity, sourceType, source, teamId);

        log.debug("List incidents request: filter={}, pageable={}, tenant={}",
                filter, pageable, tenantId);

        final var page = queryService.findAll(tenantId, filter, pageable);
        return ResponseEntity.ok(PagedResponse.of(page));
    }

    @GetMapping(
            value = "/{id}",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('RESPONDER') or hasRole('ADMIN')")
    @Operation(summary = "Get incident details")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Incident details"),
            @ApiResponse(responseCode = "404", description = "Incident not found"),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<IncidentDto> getIncident(
            @PathVariable UUID id) {
        final String tenantId = TenantContext.get();
        return ResponseEntity.ok(queryService.findById(id, tenantId));
    }

    @GetMapping(
            value = "/{id}/history",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('ROLE_RESPONDER') or hasRole('ROLE_ADMIN')")
    @Operation(summary = "Get incident status change history (audit log)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status change history"),
            @ApiResponse(responseCode = "404", description = "Incident not found"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ResponseEntity<List<IncidentHistoryDto>> getHistory(
            @PathVariable UUID id) {
        final String tenantId = TenantContext.get();
        return ResponseEntity.ok(queryService.findHistory(id, tenantId));
    }

    @PatchMapping(
            value = "/{id}/status",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('RESPONDER') or hasRole('ADMIN')")
    @Operation(summary = "Update incident status (FSM validated transition)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status updated"),
            @ApiResponse(responseCode = "400", description = "Invalid FSM transition"),
            @ApiResponse(responseCode = "404", description = "Incident not found"),
            @ApiResponse(responseCode = "409", description = "Optimistic lock conflict")
    })
    public ResponseEntity<IncidentDto> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateStatusCommand command,
            @AuthenticationPrincipal UserPrincipal principal) {

        final String tenantId = TenantContext.get();

        log.info("Status update request: incidentId={}, targetStatus={}, " +
                        "userId={}, tenant={}",
                id, command.status(), principal.userId(), tenantId);

        return ResponseEntity.ok(
                commandService.updateStatus(
                        id, command, principal.userId(), tenantId));
    }

    @PatchMapping(
            value = "/{id}/assignee",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('RESPONDER') or hasRole('ADMIN')")
    @Operation(summary = "Assign incident to a user")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Incident assignee updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed — userId is required"),
            @ApiResponse(responseCode = "404", description = "Incident not found"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ResponseEntity<IncidentDto> assignIncident(
            @PathVariable UUID id,
            @Valid @RequestBody AssignIncidentRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        final String tenantId = TenantContext.get();

        log.info("Assign incident request: incidentId={}, assignTo={}, assignedBy={}, tenant={}",
                id, request.userId(), principal.userId(), tenantId);

        return ResponseEntity.ok(
                commandService.assignTo(id, request.userId(), principal.userId(), tenantId));
    }

    @PatchMapping(
            value = "/{id}/team",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Assign a team to an incident",
            description = """
                    Assigns a team to this incident. The team should be one that
                    the calling user belongs to (verified via JWT teamIds claim)
                    unless the caller has ROLE_ADMIN.

                    When the Routing Engine is implemented (backlog), team assignment
                    will happen automatically based on routing rules. Until then,
                    this endpoint enables manual team assignment.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Team assigned"),
            @ApiResponse(responseCode = "404", description = "Incident not found")
    })
    public ResponseEntity<IncidentDto> assignTeam(
            @PathVariable("id") UUID incidentId,
            @Valid @RequestBody AssignTeamRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        final String tenantId = TenantContext.get();
        return ResponseEntity.ok(
                commandService.assignTeam(
                        incidentId, request, principal.userId(), tenantId));
    }

    @DeleteMapping(
            value = "/{id}/team",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Remove team assignment from an incident",
            description = "Removes the team assignment. Incident becomes unassigned.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Team unassigned"),
            @ApiResponse(responseCode = "404", description = "Incident not found")
    })
    public ResponseEntity<IncidentDto> unassignTeam(
            @PathVariable("id") UUID incidentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        final String tenantId = TenantContext.get();
        return ResponseEntity.ok(
                commandService.unassignTeam(
                        incidentId, principal.userId(), tenantId));
    }


}