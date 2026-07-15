package com.incidentplatform.incident.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for {@code PATCH /api/v1/incidents/{id}/team}.
 */
public record AssignTeamRequest(

        @NotNull(message = "teamId must not be null")
        UUID teamId

) {}