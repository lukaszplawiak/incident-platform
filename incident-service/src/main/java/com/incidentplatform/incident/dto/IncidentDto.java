package com.incidentplatform.incident.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.incident.domain.IncidentFsm;
import com.incidentplatform.incident.domain.IncidentStatus;
import com.incidentplatform.shared.events.SourceType;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IncidentDto(

        @JsonProperty("id")
        UUID id,

        @JsonProperty("tenantId")
        String tenantId,

        @JsonProperty("status")
        IncidentStatus status,

        @JsonProperty("title")
        String title,

        @JsonProperty("description")
        String description,

        @JsonProperty("severity")
        String severity,

        @JsonProperty("sourceType")
        SourceType sourceType,

        @JsonProperty("source")
        String source,

        @JsonProperty("alertId")
        UUID alertId,

        @JsonProperty("assignedTo")
        UUID assignedTo,

        @JsonProperty("alertFiredAt")
        Instant alertFiredAt,

        @JsonProperty("createdAt")
        Instant createdAt,

        @JsonProperty("acknowledgedAt")
        Instant acknowledgedAt,

        @JsonProperty("resolvedAt")
        Instant resolvedAt,

        @JsonProperty("closedAt")
        Instant closedAt,

        @JsonProperty("mttaMinutes")
        Long mttaMinutes,

        @JsonProperty("mttrMinutes")
        Long mttrMinutes,

        @JsonProperty("allowedTransitions")
        Set<IncidentStatus> allowedTransitions

) {
    public static IncidentDto from(Incident incident) {
        return new IncidentDto(
                incident.getId(),
                incident.getTenantId(),
                incident.getStatus(),
                incident.getTitle(),
                incident.getDescription(),
                incident.getSeverity(),
                incident.getSourceType(),
                incident.getSource(),
                incident.getAlertId(),
                incident.getAssignedTo(),
                incident.getAlertFiredAt(),
                incident.getCreatedAt(),
                incident.getAcknowledgedAt(),
                incident.getResolvedAt(),
                incident.getClosedAt(),
                incident.getMttaMinutes(),
                incident.getMttrMinutes(),
                IncidentFsm.getAllowedTransitions(incident.getStatus())
        );
    }
}