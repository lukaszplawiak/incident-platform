package com.incidentplatform.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Answer of {@code POST /api/v1/internal/api-keys/introspect}, shaped after
 * RFC 7662 (backlog #0-16): {@code {"active": false}} and nothing else for an
 * unknown, revoked or expired key, so the endpoint does not tell a caller
 * which of those it is.
 *
 * @param expiresAt {@code null} for a key that never expires; the caller must
 *                  not cache an active answer beyond it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiKeyIntrospectionResponse(
        boolean active,
        UUID keyId,
        String tenantId,
        UUID teamId,
        List<String> scopes,
        Instant expiresAt
) {

    public static ApiKeyIntrospectionResponse active(UUID keyId, String tenantId, UUID teamId,
                                                     List<String> scopes, Instant expiresAt) {
        return new ApiKeyIntrospectionResponse(true, keyId, tenantId, teamId,
                List.copyOf(scopes), expiresAt);
    }

    public static ApiKeyIntrospectionResponse inactive() {
        return new ApiKeyIntrospectionResponse(false, null, null, null, null, null);
    }
}
