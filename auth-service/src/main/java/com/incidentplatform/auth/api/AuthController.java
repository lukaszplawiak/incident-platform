package com.incidentplatform.auth.api;

import com.incidentplatform.auth.dto.LoginRequest;
import com.incidentplatform.auth.dto.LoginResponse;
import com.incidentplatform.auth.service.AuthService;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
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
}