package com.incidentplatform.auth.api;

import com.incidentplatform.auth.dto.CreateUserRequest;
import com.incidentplatform.auth.dto.CreateUserResponse;
import com.incidentplatform.auth.dto.UserSummaryDto;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "User Management",
        description = "User lifecycle management — invite, list, profile. " +
                "Admin endpoints require ROLE_ADMIN.")
@SecurityRequirement(name = "Bearer Authentication")
public class UserController {

    private final UserService userService;
    private final UserQueryService userQueryService;

    public UserController(UserService userService,
                          UserQueryService userQueryService) {
        this.userService = userService;
        this.userQueryService = userQueryService;
    }

    // ── POST /users ───────────────────────────────────────────────────────

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Create a new user and send invite",
            description = """
                    Creates a new user account and generates a single-use invite token.
                    
                    The user's password is NOT set at this point — the invited user must
                    call POST /api/v1/auth/accept-invite with the token to set their password.
                    
                    **Temporary behaviour (until email sending is implemented):**
                    The invite token is returned in the response body (`inviteToken` field).
                    The admin must securely forward this token to the new user.
                    The token expires after 72 hours.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201",
                    description = "User created — invite token in response body"),
            @ApiResponse(responseCode = "400",
                    description = "Validation failed — missing email or invalid role"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403",
                    description = "Forbidden — requires ROLE_ADMIN"),
            @ApiResponse(responseCode = "409",
                    description = "Email already exists in this tenant")
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
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "List users in tenant (paginated)",
            description = "Returns all users belonging to the current tenant. " +
                    "Default page size: 20, sorted by createdAt ascending."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User list returned"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403",
                    description = "Forbidden — requires ROLE_ADMIN")
    })
    public ResponseEntity<PagedResponse<UserSummaryDto>> listUsers(
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(userQueryService.listUsers(pageable));
    }

    // ── GET /users/me ─────────────────────────────────────────────────────

    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get own profile",
            description = "Returns the authenticated user's own profile. " +
                    "Available to all authenticated users regardless of role."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile returned"),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    public ResponseEntity<UserSummaryDto> getMe(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(userQueryService.getMe(principal));
    }
}