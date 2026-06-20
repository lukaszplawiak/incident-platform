package com.incidentplatform.incident.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.incident.domain.IncidentFsm;
import com.incidentplatform.incident.domain.IncidentStatus;
import com.incidentplatform.shared.domain.Severity;
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
        Severity severity,

        @JsonProperty("sourceType")
        SourceType sourceType,

        @JsonProperty("source")
        String source,

        @JsonProperty("alertId")
        UUID alertId,

        @JsonProperty("assignedTo")
        UUID assignedTo,

        @JsonProperty("escalationLevel")
        int escalationLevel,

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
        // alertFingerprint is intentionally excluded from this response.
        //
        // It is an internal implementation detail of the ingestion-service deduplication
        // mechanism — no API client can act on it, and no endpoint accepts it as input.
        // Exposing internal identifiers through a public API surface is poor design
        // regardless of security considerations: clients should only receive data
        // they can actually use.
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
                        incident.getEscalationLevel(),
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