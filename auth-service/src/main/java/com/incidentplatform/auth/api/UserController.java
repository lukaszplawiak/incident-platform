package com.incidentplatform.auth.api;

import com.incidentplatform.auth.dto.ChangePasswordRequest;
import com.incidentplatform.auth.dto.CreateUserRequest;
import com.incidentplatform.auth.dto.CreateUserResponse;
import com.incidentplatform.auth.dto.UpdateUserRolesRequest;
import com.incidentplatform.auth.dto.UpdateUserStatusRequest;
import com.incidentplatform.auth.dto.UserSummaryDto;
import com.incidentplatform.auth.service.PasswordService;
import com.incidentplatform.auth.service.ResendInviteService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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
        description = "User lifecycle: invite, list, profile, roles, status, password. " +
                "Admin-only endpoints require ROLE_ADMIN.")
@SecurityRequirement(name = "Bearer Authentication")
public class UserController {

    private final UserService userService;
    private final UserQueryService userQueryService;
    private final UserManagementService userManagementService;
    private final PasswordService passwordService;
    private final ResendInviteService resendInviteService;

    public UserController(UserService userService,
                          UserQueryService userQueryService,
                          UserManagementService userManagementService,
                          PasswordService passwordService,
                          ResendInviteService resendInviteService) {
        this.userService = userService;
        this.userQueryService = userQueryService;
        this.userManagementService = userManagementService;
        this.passwordService = passwordService;
        this.resendInviteService = resendInviteService;
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
                    their password. Token expires after 7 days.
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
                    Replaces all current roles with the provided set (atomic - not additive).
                    Sending ["ROLE_ADMIN"] removes any existing ROLE_RESPONDER.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Roles updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
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
            @ApiResponse(responseCode = "200", description = "Status updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden — ROLE_ADMIN required"),
            @ApiResponse(responseCode = "404", description = "User not found in tenant")
    })
    public ResponseEntity<UserSummaryDto> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserStatusRequest request) {
        return ResponseEntity.ok(userManagementService.updateStatus(id, request));
    }


    // ── POST /users/{id}/resend-invite ───────────────────────────────────

    @PostMapping(value = "/{id}/resend-invite")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Operation(
            summary = "Resend invite email",
            description = """
                    Resends the invite email to a user who has not yet accepted
                    their invitation. Generates a fresh token (new 7-day TTL),
                    invalidates all previously issued invite tokens, and queues
                    a new email for dispatch within 30 seconds.

                    Returns 409 if:
                    - The user has already accepted the invite (has a password)
                    - An invite email is already queued (PENDING — wait 30s)
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Invite email queued for dispatch"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden — ROLE_ADMIN required"),
            @ApiResponse(responseCode = "404", description = "User not found in tenant"),
            @ApiResponse(responseCode = "409", description = "Invite already accepted, or dispatch already pending")
    })
    public ResponseEntity<Void> resendInvite(@PathVariable UUID id) {
        resendInviteService.resendInvite(id);
        return ResponseEntity.accepted().build();
    }

    // ── DELETE /users/{id} ────────────────────────────────────────────────

    @DeleteMapping(value = "/{id}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Operation(
            summary = "Archive a user",
            description = """
                    Archives a user — hides from all normal queries but preserves
                    the record and all historical references (audit logs, incidents).
                    Reversible via POST /api/v1/users/{id}/restore.

                    For permanent GDPR erasure of personal data, use
                    POST /api/v1/users/{id}/anonymize after archiving.

                    Admins cannot archive their own account.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "User soft-deleted successfully"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden — ROLE_ADMIN required, " +
                    "or attempting to delete own account"),
            @ApiResponse(responseCode = "404", description = "User not found in tenant")
    })
    public ResponseEntity<Void> deleteUser(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        userManagementService.archiveUser(id, principal);
        return ResponseEntity.noContent().build();
    }

    // ── PATCH /users/me/password ──────────────────────────────────────────

    @PatchMapping(
            value = "/me/password",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Change own password",
            description = """
                    Changes the authenticated user's own password.
                    Requires the current password to prevent session-hijacking attacks:
                    an attacker with a stolen JWT cannot change the password without
                    knowing the current one.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password changed successfully"),
            @ApiResponse(responseCode = "400", description = "Validation failed — missing fields or password too short"),
            @ApiResponse(responseCode = "401", description = "Unauthorized or wrong current password")
    })
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        passwordService.changePassword(principal, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{id}/restore")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Operation(
            summary = "Restore an archived user",
            description = """
                    Restores an archived user to active state.
                    The user can log in again after restore.

                    Returns 409 if the user is anonymized (irreversible).
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "User restored"),
            @ApiResponse(responseCode = "404", description = "User not found or not archived"),
            @ApiResponse(responseCode = "409", description = "User is anonymized — cannot restore")
    })
    public ResponseEntity<Void> restoreUser(
            @PathVariable("id") UUID userId,
            @AuthenticationPrincipal UserPrincipal principal) {
        userManagementService.restoreUser(userId, principal);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{id}/anonymize")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Operation(
            summary = "Anonymize a user (GDPR erasure) — IRREVERSIBLE",
            description = """
                    Permanently anonymizes the user's personal data for GDPR compliance.

                    This operation is IRREVERSIBLE. It:
                    - Replaces email with an anonymous alias
                    - Removes password hash
                    - Removes all roles and team memberships

                    The user UUID is preserved so that historical audit records
                    and incident assignments remain valid.

                    The user must be archived first (DELETE /api/v1/users/{id}).

                    Returns 409 if the user is active or already anonymized.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "User anonymized"),
            @ApiResponse(responseCode = "404", description = "User not found"),
            @ApiResponse(responseCode = "409",
                    description = "User is active (archive first) or already anonymized")
    })
    public ResponseEntity<Void> anonymizeUser(
            @PathVariable("id") UUID userId,
            @AuthenticationPrincipal UserPrincipal principal) {
        userManagementService.anonymizeUser(userId, principal);
        return ResponseEntity.noContent().build();
    }


}