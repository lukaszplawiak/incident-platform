package com.incidentplatform.auth.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/api-keys/revoke-created-by} (backlog
 * #0-89): revoke every active key of the tenant that this user created.
 */
public record RevokeKeysCreatedByRequest(

        @NotNull(message = "userId is required")
        UUID userId,

        /**
         * Only keys created at or after this time; absent = every key the user
         * created. Typically the start of the suspected compromise.
         */
        Instant since

) {}
