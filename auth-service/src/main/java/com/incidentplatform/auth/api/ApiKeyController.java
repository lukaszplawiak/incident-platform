package com.incidentplatform.auth.api;

import com.incidentplatform.auth.dto.ApiKeyCreatedResponse;
import com.incidentplatform.auth.dto.ApiKeyDto;
import com.incidentplatform.auth.dto.CreateApiKeyRequest;
import com.incidentplatform.auth.service.ApiKeyService;
import com.incidentplatform.shared.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/api-keys")
@Tag(name = "API Keys",
        description = "Long-lived credentials for machine-to-machine integrations.")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Create an API key",
            description = """
                    Creates a new API key. The raw key is returned ONCE in the
                    response — it cannot be retrieved again. Store it securely.

                    Key types:
                    - TENANT: ADMIN only. Not bound to a user. Survives user departure.
                    - PERSONAL: Any user. Bound to caller. Revoked when user is archived.

                    Scope rules:
                    - PERSONAL keys cannot be granted scopes exceeding caller's role.
                    - ROLE_RESPONDER cannot create keys with teams:write scope.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Key created — raw key in response"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions for key type or scope"),
            @ApiResponse(responseCode = "422", description = "Key limit reached")
    })
    public ResponseEntity<ApiKeyCreatedResponse> createApiKey(
            @Valid @RequestBody CreateApiKeyRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(apiKeyService.createApiKey(request, principal));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "List API keys",
            description = """
                    ADMIN: lists all active keys in the tenant.
                    RESPONDER: lists only their own personal keys.
                    Never returns the raw key — only metadata.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Keys listed")
    })
    public ResponseEntity<List<ApiKeyDto>> listApiKeys(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(apiKeyService.listApiKeys(principal));
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Revoke an API key",
            description = """
                    Revokes a key immediately. Requests using the revoked key
                    will be rejected with 401 on the next request.

                    ADMIN: can revoke any key in the tenant.
                    RESPONDER: can only revoke their own personal keys.

                    Revoked keys are preserved in the database for audit purposes
                    and are not hard-deleted.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Key revoked"),
            @ApiResponse(responseCode = "403", description = "Cannot revoke this key"),
            @ApiResponse(responseCode = "404", description = "Key not found"),
            @ApiResponse(responseCode = "409", description = "Key already revoked")
    })
    public ResponseEntity<Void> revokeApiKey(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        apiKeyService.revokeApiKey(id, principal);
        return ResponseEntity.noContent().build();
    }
}