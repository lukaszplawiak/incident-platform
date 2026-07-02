package com.incidentplatform.auth.api;

import com.incidentplatform.auth.dto.AcceptInviteRequest;
import com.incidentplatform.auth.dto.LoginRequest;
import com.incidentplatform.auth.dto.LoginResponse;
import com.incidentplatform.auth.service.AuthService;
import com.incidentplatform.auth.service.InviteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication",
        description = "JWT token issuance for human operators. " +
                "Service-to-service tokens are issued by ServiceTokenProvider internally.")
public class AuthController {

    private final AuthService authService;
    private final InviteService inviteService;

    public AuthController(AuthService authService, InviteService inviteService) {
        this.authService = authService;
        this.inviteService = inviteService;
    }

    @PostMapping(
            value = "/login",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Login with email and password",
            description = "Authenticates a human operator and returns a JWT. " +
                    "The tenant is resolved from the X-Tenant-Id request header. " +
                    "Include the returned token in subsequent requests as: " +
                    "Authorization: Bearer <token>"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login successful — JWT returned"),
            @ApiResponse(responseCode = "400", description = "Validation failed — email or password missing"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials")
    })
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping(
            value = "/accept-invite",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Accept invite and set password",
            description = """
                    Completes user registration by setting a password using the invite token
                    received from the admin.
                    
                    The token is single-use and expires after 72 hours.
                    After a successful call, the user can log in via POST /api/v1/auth/login.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204",
                    description = "Password set — user can now log in"),
            @ApiResponse(responseCode = "400",
                    description = "Validation failed — missing token or password too short"),
            @ApiResponse(responseCode = "401",
                    description = "Token is invalid, expired, or already used")
    })
    public ResponseEntity<Void> acceptInvite(
            @Valid @RequestBody AcceptInviteRequest request) {
        inviteService.acceptInvite(request);
        return ResponseEntity.noContent().build();
    }
}