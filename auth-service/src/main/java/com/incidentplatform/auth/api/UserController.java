package com.incidentplatform.auth.api;

import com.incidentplatform.auth.dto.CreateUserRequest;
import com.incidentplatform.auth.dto.CreateUserResponse;
import com.incidentplatform.auth.dto.UpdateUserRolesRequest;
import com.incidentplatform.auth.dto.UpdateUserStatusRequest;
import com.incidentplatform.auth.dto.UserSummaryDto;
import com.incidentplatform.auth.service.UserManagementService;
import com.incidentplatform.auth.service.UserQueryService;
import com.incidentplatform.auth.service.UserService;
import com.incidentplatform.shared.dto.PagedResponse;
import com.incidentplatform.shared.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "User Management",
        description = "User lifecycle: invite, list, profile, roles, status. " +
                "Admin-only endpoints require ROLE_ADMIN.")
@SecurityRequirement(name = "Bearer Authentication")
public class UserController {

    private final UserService userService;
    private final UserQueryService userQueryService;
    private final UserManagementService userManagementService;

    public UserController(UserService userService,
                          UserQueryService userQueryService,
                          UserManagementService userManagementService) {
        this.userService = userService;
        this.userQueryService = userQueryService;
        this.userManagementService = userManagementService;
    }

    // ── POST /users ───────────────────────────────────────────────────────

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Operation(summary = "Create a new user and generate invite token",
            description = """
                    Creates a new user account without a password.
                    The invite token in the response must be shared securely with
                    the new user — they call POST /api/v1/auth/accept-invite to set
                    their password. Token expires after 72 hours.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User created"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden — ROLE_ADMIN required"),
            @ApiResponse(responseCode = "409", description = "Email already exists in tenant")
    })
    public ResponseEntity<CreateUserResponse> createUser(
            @Valid @RequestBody CreateUserRequest request) {

        final CreateUserResponse response = userService.createUser(request);

        final URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.userId())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    // ── GET /users ────────────────────────────────────────────────────────

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Operation(summary = "List all users in tenant (paginated)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User list returned"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden — ROLE_ADMIN required")
    })
    public ResponseEntity<PagedResponse<UserSummaryDto>> listUsers(
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(userQueryService.listUsers(pageable));
    }

    // ── GET /users/me ─────────────────────────────────────────────────────

    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get own profile",
            description = "Available to all authenticated users regardless of role.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile returned"),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<UserSummaryDto> getMe(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(userQueryService.getMe(principal));
    }

    // ── PATCH /users/{id}/roles ───────────────────────────────────────────

    @PatchMapping(
            value = "/{id}/roles",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Operation(summary = "Replace user roles",
            description = """
                    Replaces all current roles with the provided set (not additive).
                    Sending ["ROLE_ADMIN"] removes any existing ROLE_RESPONDER.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Roles updated — full user returned"),
            @ApiResponse(responseCode = "400", description = "Validation failed — empty or invalid roles"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden — ROLE_ADMIN required"),
            @ApiResponse(responseCode = "404", description = "User not found in tenant")
    })
    public ResponseEntity<UserSummaryDto> updateRoles(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRolesRequest request) {
        return ResponseEntity.ok(userManagementService.updateRoles(id, request));
    }

    // ── PATCH /users/{id}/status ──────────────────────────────────────────

    @PatchMapping(
            value = "/{id}/status",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Operation(summary = "Activate or deactivate a user",
            description = """
                    Sets the user's active flag. Deactivated users cannot log in.
                    The user record is preserved — audit trail references remain intact.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status updated — full user returned"),
            @ApiResponse(responseCode = "400", description = "Validation failed — active field missing"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden — ROLE_ADMIN required"),
            @ApiResponse(responseCode = "404", description = "User not found in tenant")
    })
    public ResponseEntity<UserSummaryDto> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserStatusRequest request) {
        return ResponseEntity.ok(userManagementService.updateStatus(id, request));
    }
}