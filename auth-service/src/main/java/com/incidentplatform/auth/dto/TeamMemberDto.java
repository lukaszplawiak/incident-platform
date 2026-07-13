package com.incidentplatform.auth.dto;

import com.incidentplatform.auth.domain.TeamMember;
import com.incidentplatform.auth.domain.TeamRole;

import java.time.Instant;
import java.util.UUID;

/**
 * Team member representation returned by member list endpoints.
 */
public record TeamMemberDto(
        UUID userId,
        String email,
        TeamRole teamRole,
        Instant joinedAt
) {
    public static TeamMemberDto from(TeamMember member) {
        return new TeamMemberDto(
                member.getUser().getId(),
                member.getUser().getEmail(),
                member.getTeamRole(),
                member.getJoinedAt());
    }
}