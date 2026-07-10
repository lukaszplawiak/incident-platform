package com.incidentplatform.auth.api;

import com.incidentplatform.auth.dto.AcceptInviteRequest;
import com.incidentplatform.auth.dto.RefreshRequest;
import com.incidentplatform.auth.dto.RefreshResponse;
import com.incidentplatform.auth.dto.LoginRequest;
import com.incidentplatform.auth.dto.LoginResponse;
import com.incidentplatform.auth.service.AuthService;
import com.incidentplatform.auth.service.AuthTokenService;
import com.incidentplatform.auth.service.InviteService;
import com.incidentplatform.auth.service.LogoutService;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final AuthTokenService authTokenService;
    private final InviteService inviteService;
    private final LogoutService logoutService;

    public AuthController(AuthService authService,
                          AuthTokenService authTokenService,
                          InviteService inviteService,
                          LogoutService logoutService) {
        this.authService      = authService;
        this.authTokenService = authTokenService;
        this.inviteService    = inviteService;
        this.logoutService    = logoutService;
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
            @ApiResponse(responseCode = "401", description = "Invalid credentials or account lock")
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


    @PostMapping(
            value = "/refresh",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Refresh access token",
            description = """
                    Exchanges a valid refresh token for a new access token and
                    a new refresh token (rotation).

                    The old refresh token is immediately invalidated — the client
                    must replace it with the new one before the next refresh call.

                    If the refresh token has been used already (possible replay attack),
                    returns 401. The client must re-authenticate via POST /auth/login.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "New access token and refresh token issued"),
            @ApiResponse(responseCode = "400",
                    description = "Validation failed — refreshToken missing"),
            @ApiResponse(responseCode = "401",
                    description = "Refresh token invalid, expired, or already used")
    })
    public ResponseEntity<RefreshResponse> refresh(
            @Valid @RequestBody RefreshRequest request) {
        final AuthTokenService.RotationResult result =
                authTokenService.rotateRefreshToken(request.refreshToken());

        return ResponseEntity.ok(new RefreshResponse(
                result.accessToken(),
                result.rawRefreshToken(),
                result.user().getId(),
                result.user().getTenantId(),
                result.user().getEmail(),
                result.user().getRoleNames(),
                result.accessExpiresAt(),
                result.refreshExpiresAt()
        ));
    }

    @PostMapping(value = "/logout")
    @Operation(
            summary = "Logout — revoke current session token",
            description = """
                    Revokes the JWT submitted in the Authorization header.
                    Token is added to Redis revocation list — rejected on all
                    subsequent requests even before natural expiry.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Logged out — token revoked"),
            @ApiResponse(responseCode = "401", description = "Unauthorized or invalid token")
    })
    public ResponseEntity<Void> logout(
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest request) {

        final String authHeader = request.getHeader("Authorization");
        final String rawToken = authHeader != null && authHeader.startsWith("Bearer ")
                ? authHeader.substring(7) : null;

        if (rawToken == null) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED,
                    "No token provided", HttpStatus.UNAUTHORIZED);
        }

        logoutService.logout(rawToken, principal);
        return ResponseEntity.noContent().build();
    }
}