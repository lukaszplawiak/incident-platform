package com.incidentplatform.incident.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AssignIncidentRequest(

        @NotNull(message = "userId is required")
        @JsonProperty("userId")
        UUID userId

) {}