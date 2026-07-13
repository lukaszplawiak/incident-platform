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
 */
public record UserSummaryDto(
        UUID id,
        String tenantId,
        String email,
        List<String> roles,
        List<UUID> teamIds,
        boolean active,
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
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}