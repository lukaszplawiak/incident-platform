package com.incidentplatform.auth.api;

import com.incidentplatform.auth.dto.CreateIntegrationRequest;
import com.incidentplatform.auth.dto.IntegrationCreatedResponse;
import com.incidentplatform.auth.dto.IntegrationDto;
import com.incidentplatform.auth.service.IntegrationService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integrations")
@Tag(name = "Integrations",
        description = "Monitoring system integrations — named connections between " +
                "external alert sources (Prometheus, Grafana, Wazuh) and Teams.")
public class IntegrationController {

    private final IntegrationService integrationService;

    public IntegrationController(IntegrationService integrationService) {
        this.integrationService = integrationService;
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Operation(
            summary = "Create an integration",
            description = """
                    Creates a named connection between an external monitoring system
                    and a Team. Automatically generates an API key for the external
                    system to use when sending alerts.

                    The API key is returned ONCE in the response — store it securely
                    and configure it in the monitoring system immediately.

                    Alertmanager configuration example:
                      receivers:
                        - name: incident-platform
                          webhook_configs:
                            - url: 'https://api.example.com/api/v1/alerts/prometheus'
                              http_config:
                                authorization:
                                  type: ApiKey
                                  credentials: '<apiKey from response>'

                    Alert routing chain:
                      ApiKey → Integration → Team → OncallSchedule → Notification
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201",
                    description = "Integration created — API key in response (shown once)"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "403", description = "ADMIN role required"),
            @ApiResponse(responseCode = "404", description = "Team not found"),
            @ApiResponse(responseCode = "409", description = "Integration name already exists")
    })
    public ResponseEntity<IntegrationCreatedResponse> createIntegration(
            @Valid @RequestBody CreateIntegrationRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(integrationService.createIntegration(request, principal));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Operation(
            summary = "List integrations",
            description = "Lists all active integrations in the tenant. " +
                    "Never returns the API key — only metadata and key prefix.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Integrations listed")
    })
    public ResponseEntity<List<IntegrationDto>> listIntegrations() {
        return ResponseEntity.ok(integrationService.listIntegrations());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Operation(
            summary = "Revoke an integration",
            description = """
                    Revokes the integration and its API key immediately.
                    The external monitoring system will receive 401 on its next alert.

                    After revoking, create a new integration and update the monitoring
                    system configuration with the new API key.

                    The integration and its key are preserved in the database for audit.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Integration revoked"),
            @ApiResponse(responseCode = "404", description = "Integration not found"),
            @ApiResponse(responseCode = "409", description = "Integration already revoked")
    })
    public ResponseEntity<Void> revokeIntegration(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        integrationService.revokeIntegration(id, principal);
        return ResponseEntity.noContent().build();
    }
}