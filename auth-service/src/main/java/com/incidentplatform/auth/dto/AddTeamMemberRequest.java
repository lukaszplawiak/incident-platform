package com.incidentplatform.auth.dto;

import com.incidentplatform.auth.domain.TeamRole;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/teams/{teamId}/members}.
 */
public record AddTeamMemberRequest(

        @NotNull(message = "userId must not be null")
        UUID userId,

        @NotNull(message = "teamRole must not be null")
        TeamRole teamRole

) {}