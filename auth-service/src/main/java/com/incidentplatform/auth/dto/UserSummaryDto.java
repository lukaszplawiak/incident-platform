package com.incidentplatform.auth.dto;

import com.incidentplatform.auth.domain.User;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * User representation returned by list and detail endpoints.
 *
 * <p>Never includes {@code passwordHash} — that field is internal and
 * must never be exposed via API, regardless of the caller's role.
 *
 * <p>{@code mfaEnabled} added — GET /users/me returns this DTO and is
 * the endpoint the frontend's self-service MFA settings screen calls to
 * decide whether to show "Set up MFA" or "MFA enabled, manage / disable".
 * User.isMfaEnabled() existed on the domain entity already; this DTO
 * simply hadn't been asked for it before.
 */
public record UserSummaryDto(
        UUID id,
        String tenantId,
        String email,
        List<String> roles,
        List<UUID> teamIds,
        boolean active,
        boolean mfaEnabled,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * Creates a UserSummaryDto without team information.
     * Use {@link #from(User, List)} when team IDs are available.
     */
    public static UserSummaryDto from(User user) {
        return from(user, List.of());
    }

    public static UserSummaryDto from(User user, List<UUID> teamIds) {
        return new UserSummaryDto(
                user.getId(),
                user.getTenantId(),
                user.getEmail(),
                user.getRoleNames(),
                teamIds,
                user.isActive(),
                user.isMfaEnabled(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}