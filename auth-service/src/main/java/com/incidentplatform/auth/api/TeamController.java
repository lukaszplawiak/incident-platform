package com.incidentplatform.auth.api;

import com.incidentplatform.auth.domain.TeamRole;
import com.incidentplatform.auth.dto.AddTeamMemberRequest;
import com.incidentplatform.auth.dto.CreateTeamRequest;
import com.incidentplatform.auth.dto.TeamDto;
import com.incidentplatform.auth.dto.TeamMemberDto;
import com.incidentplatform.auth.service.TeamService;
import com.incidentplatform.shared.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/teams")
@Tag(name = "Teams", description = "Team management — groups of users within a tenant.")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a team")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Team created"),
            @ApiResponse(responseCode = "409", description = "Name already exists")})
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TeamDto> createTeam(
            @Valid @RequestBody CreateTeamRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(teamService.createTeam(request, principal));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List all active teams in the current tenant")
    public ResponseEntity<List<TeamDto>> listTeams() {
        return ResponseEntity.ok(teamService.listTeams());
    }

    @GetMapping(value = "/{teamId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get a team by ID")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Team found"),
            @ApiResponse(responseCode = "404", description = "Team not found")})
    public ResponseEntity<TeamDto> getTeam(@PathVariable UUID teamId) {
        return ResponseEntity.ok(teamService.getTeam(teamId));
    }

    @DeleteMapping("/{teamId}")
    @Operation(summary = "Archive a team",
            description = """
                       Archives the team — hides from queries but preserves record
                       and memberships. Reversible via POST /{teamId}/restore.
                       ADMIN only.
                       """)
    @ApiResponses({@ApiResponse(responseCode = "204", description = "Team archived"),
            @ApiResponse(responseCode = "404", description = "Team not found")})
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> archiveTeam(
            @PathVariable UUID teamId,
            @AuthenticationPrincipal UserPrincipal principal) {
        teamService.archiveTeam(teamId, principal);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{teamId}/restore")
    @Operation(summary = "Restore an archived team",
            description = """
                       Restores an archived team. Memberships are preserved during
                       archiving and restored automatically.
                       Returns 409 if a name conflict with an active team exists.
                       ADMIN only.
                       """)
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Team restored"),
            @ApiResponse(responseCode = "404", description = "Archived team not found"),
            @ApiResponse(responseCode = "409", description = "Name conflict")})
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TeamDto> restoreTeam(
            @PathVariable UUID teamId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(teamService.restoreTeam(teamId, principal));
    }

    @PostMapping(value = "/{teamId}/members",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Add a member to a team")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Member added"),
            @ApiResponse(responseCode = "409", description = "Already a member")})
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TeamMemberDto> addMember(
            @PathVariable UUID teamId,
            @Valid @RequestBody AddTeamMemberRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(teamService.addMember(teamId, request, principal));
    }

    @GetMapping(value = "/{teamId}/members", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List members of a team")
    public ResponseEntity<List<TeamMemberDto>> listMembers(@PathVariable UUID teamId) {
        return ResponseEntity.ok(teamService.listMembers(teamId));
    }

    @PatchMapping(value = "/{teamId}/members/{userId}/role",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update a member's team role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TeamMemberDto> updateMemberRole(
            @PathVariable UUID teamId,
            @PathVariable UUID userId,
            @RequestParam TeamRole teamRole,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(
                teamService.updateMemberRole(teamId, userId, teamRole, principal));
    }

    @DeleteMapping("/{teamId}/members/{userId}")
    @Operation(summary = "Remove a member from a team")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> removeMember(
            @PathVariable UUID teamId,
            @PathVariable UUID userId,
            @AuthenticationPrincipal UserPrincipal principal) {
        teamService.removeMember(teamId, userId, principal);
        return ResponseEntity.noContent().build();
    }
}