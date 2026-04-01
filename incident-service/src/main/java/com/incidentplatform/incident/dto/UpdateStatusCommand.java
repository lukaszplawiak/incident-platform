package com.incidentplatform.incident.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.incidentplatform.incident.domain.IncidentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateStatusCommand(

        @NotNull(message = "Target status is required")
        @JsonProperty("status")
        IncidentStatus status,

        @Size(max = 1000, message = "Comment must not exceed 1000 characters")
        @JsonProperty("comment")
        String comment

) {}