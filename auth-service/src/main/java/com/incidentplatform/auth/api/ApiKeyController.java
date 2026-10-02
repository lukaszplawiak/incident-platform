package com.incidentplatform.auth.api;

import com.incidentplatform.auth.dto.ApiKeyCreatedResponse;
import com.incidentplatform.auth.dto.ApiKeyDto;
import com.incidentplatform.auth.dto.CreateApiKeyRequest;
import com.incidentplatform.auth.dto.RevokeKeysCreatedByRequest;
import com.incidentplatform.auth.dto.RevokedApiKeysResponse;
import com.incidentplatform.auth.ratelimit.RateLimitRefusedException;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
                    - PERSONAL: Any user. Bound to caller. Revoked when user is archived,
                      and by a password reset, an admin MFA reset or the break-glass
                      reset of its owner (backlog #0-89).

                    Scope rules:
                    - PERSONAL keys cannot be granted scopes exceeding caller's role.
                    - ROLE_RESPONDER cannot create keys with teams:write scope.

                    Where a key works: in auth-service only the /api/v1/teams routes, with
                    teams:read / teams:write; every other auth-service route refuses a key,
                    this one included.

                    Every new key is emailed to the account (a TENANT key: the admin who
                    created it), one email per key, showing its id. At most
                    20 keys per user per hour (api-keys.creation-limit.per-user-per-hour),
                    revoked ones included: 429 with Retry-After beyond that.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Key created — raw key in response"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "403", description = "Insufficient permissions for key type or scope"),
            @ApiResponse(responseCode = "422", description = "Key limit reached"),
            @ApiResponse(responseCode = "429", description = "Hourly creation limit reached; see Retry-After")
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
                    ADMIN: lists all active keys in the tenant; with createdBy, only
                    the keys that user created (backlog #0-89).
                    RESPONDER: lists only their own personal keys (createdBy ignored).
                    Never returns the raw key — only metadata.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Keys listed")
    })
    public ResponseEntity<List<ApiKeyDto>> listApiKeys(
            @RequestParam(required = false) UUID createdBy,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(apiKeyService.listApiKeys(principal, createdBy));
    }

    @PostMapping(
            value = "/revoke-created-by",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Revoke every key a user created",
            description = """
                    Revokes every active API key of the tenant that the user created,
                    since the given time if one is given (backlog #0-89): personal,
                    tenant and integration keys, the integrations with their keys.

                    For recovering a taken-over account: a password or MFA reset revokes
                    only personal keys, as tenant and integration keys are meant to
                    outlive their creator. List them first with GET ?createdBy=.
                    Tenant and integration keys created before this was recorded have no
                    creator and are not matched; revoke them one by one. A revoked
                    integration key may still be accepted by ingestion for up to 60 s
                    (its introspection cache). ADMIN, from a login.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Keys revoked (possibly none)"),
            @ApiResponse(responseCode = "403", description = "Not an admin, or an API key"),
            @ApiResponse(responseCode = "404", description = "User not in the tenant")
    })
    public ResponseEntity<RevokedApiKeysResponse> revokeKeysCreatedBy(
            @Valid @RequestBody RevokeKeysCreatedByRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(apiKeyService.revokeKeysCreatedBy(request.userId(), request.since(), principal));
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

    /** Backlog #0-89: the hourly key creation limit (ApiKeyCreationLimit): 429 with Retry-After. */
    @ExceptionHandler(RateLimitRefusedException.class)
    ResponseEntity<Void> rateLimited(RateLimitRefusedException refused) {
        return RateLimitResponses.refused(refused);
    }
}
