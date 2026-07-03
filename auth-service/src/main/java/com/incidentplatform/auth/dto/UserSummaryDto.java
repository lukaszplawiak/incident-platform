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
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public static UserSummaryDto from(User user) {
        return new UserSummaryDto(
                user.getId(),
                user.getTenantId(),
                user.getEmail(),
                user.getRoleNames(),
                user.isActive(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}