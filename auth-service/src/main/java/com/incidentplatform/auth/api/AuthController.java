package com.incidentplatform.auth.api;

import com.incidentplatform.auth.dto.AcceptInviteRequest;
import com.incidentplatform.auth.dto.ForgotPasswordRequest;
import com.incidentplatform.auth.dto.LoginRequest;
import com.incidentplatform.auth.dto.LoginResponse;
import com.incidentplatform.auth.dto.RefreshRequest;
import com.incidentplatform.auth.dto.RefreshResponse;
import com.incidentplatform.auth.dto.ResetPasswordRequest;
import com.incidentplatform.auth.service.AuthService;
import com.incidentplatform.auth.service.AuthTokenService;
import com.incidentplatform.auth.service.ForgotPasswordService;
import com.incidentplatform.auth.service.InviteService;
import com.incidentplatform.auth.service.LogoutService;
import com.incidentplatform.auth.service.PasswordService;
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
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final ForgotPasswordService forgotPasswordService;
    private final PasswordService passwordService;

    public AuthController(AuthService authService,
                          AuthTokenService authTokenService,
                          InviteService inviteService,
                          LogoutService logoutService,
                          ForgotPasswordService forgotPasswordService,
                          PasswordService passwordService) {
        this.authService           = authService;
        this.authTokenService      = authTokenService;
        this.inviteService         = inviteService;
        this.logoutService         = logoutService;
        this.forgotPasswordService = forgotPasswordService;
        this.passwordService       = passwordService;
    }

    @PostMapping(
            value = "/login",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "Login with email and password")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login successful"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials or account locked")
    })
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping(
            value = "/accept-invite",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "Accept invite and set password")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password set"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "401", description = "Token invalid, expired, or already used")
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
    @Operation(summary = "Refresh access token")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New token pair issued"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "401", description = "Refresh token invalid or already used")
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

    @PostMapping(
            value = "/forgot-password",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Request password reset",
            description = """
                    Always returns 202 Accepted regardless of whether the email has an
                    account — prevents user enumeration attacks.
                    If the email exists, a reset link is sent within 30 seconds (15-min TTL).
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202",
                    description = "Request received — reset email sent if account exists"),
            @ApiResponse(responseCode = "400",
                    description = "Validation failed — email missing or malformed")
    })
    public ResponseEntity<Void> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            @RequestHeader(value = "X-Tenant-Id",
                    required = false,
                    defaultValue = "default") String tenantId) {
        // X-Tenant-Id is read directly from the header because this is a public
        // endpoint — JwtAuthFilter.shouldNotFilter() skips it, so TenantContext
        // is never populated for unauthenticated requests.
        forgotPasswordService.initiateReset(request.email(), tenantId);
        // Always 202 — user enumeration protection (layer 1).
        return ResponseEntity.accepted().build();
    }

    @PostMapping(
            value = "/reset-password",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Reset password using token from email",
            description = """
                    Token is single-use, expires after 15 minutes.
                    On success, all active sessions (refresh tokens) are invalidated.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password reset"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "401", description = "Token invalid, expired, or already used")
    })
    public ResponseEntity<Void> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        passwordService.resetPassword(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/logout")
    @Operation(summary = "Logout — revoke current session token")
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