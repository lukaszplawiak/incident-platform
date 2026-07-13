package com.incidentplatform.auth.dto;

import com.incidentplatform.auth.domain.Team;
import com.incidentplatform.auth.domain.TeamMember;
import com.incidentplatform.auth.domain.TeamRole;

import java.time.Instant;
import java.util.UUID;

/**
 * Team representation returned by list and detail endpoints.
 */
public record TeamDto(
        UUID id,
        String tenantId,
        String name,
        String description,
        Instant createdAt
) {
    public static TeamDto from(Team team) {
        return new TeamDto(
                team.getId(),
                team.getTenantId(),
                team.getName(),
                team.getDescription(),
                team.getCreatedAt());
    }
}