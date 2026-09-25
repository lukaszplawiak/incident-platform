package com.incidentplatform.auth.api;

import com.incidentplatform.auth.dto.ApiKeyIntrospectionRequest;
import com.incidentplatform.auth.dto.ApiKeyIntrospectionResponse;
import com.incidentplatform.auth.service.ApiKeyIntrospectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * API key introspection for ingestion-service (backlog #0-16).
 *
 * <p>External alert sources (a tenant's Alertmanager, Wazuh, ...) authenticate
 * to ingestion-service with an Integration API key. ingestion-service does not
 * own the keys, so it sends the key's SHA-256 hash here and caches the answer
 * briefly — the narrow HTTP pull of backlog #0-30, not a Kafka replica of the
 * key table. Only TENANT keys (Integration keys and tenant-wide keys an ADMIN
 * created) introspect as active; a PERSONAL key is answered like an unknown
 * one ({@code ApiKeyIntrospectionService}).
 *
 * <p>The caller cannot know the tenant before asking, so it authenticates with
 * a purpose token ({@code TokenPurposes.API_KEY_INTROSPECTION}) that acts for
 * no tenant; its {@code IntrospectionPrincipal} is accepted here and denied on
 * every other route ({@code SecurityConfig}). The endpoint always answers 200
 * for a well-formed request: whether the key is active is the answer, not an
 * error.
 */
@RestController
public class ApiKeyIntrospectionController {

    private final ApiKeyIntrospectionService introspectionService;

    public ApiKeyIntrospectionController(ApiKeyIntrospectionService introspectionService) {
        this.introspectionService = introspectionService;
    }

    @PostMapping(value = "/api/v1/internal/api-keys/introspect",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('API_KEY_INTROSPECTION')")
    @Operation(
            summary = "[Internal] Resolve an API key hash to its tenant, team and scopes",
            description = "Service-to-service only (backlog #0-16). Called by ingestion-service " +
                    "with a purpose token to authenticate Integration API keys.",
            hidden = true)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "{active:true, ...} or {active:false}"),
            @ApiResponse(responseCode = "400", description = "keyHash is not a SHA-256 hex digest"),
            @ApiResponse(responseCode = "403", description = "API key introspection token required")
    })
    public ApiKeyIntrospectionResponse introspect(
            @Valid @RequestBody ApiKeyIntrospectionRequest request) {
        return introspectionService.introspect(request.keyHash());
    }
}
