package com.incidentplatform.incident.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.incidentplatform.incident.domain.IncidentHistory;
import com.incidentplatform.incident.domain.IncidentStatus;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IncidentHistoryDto(

        @JsonProperty("id")
        UUID id,

        @JsonProperty("fromStatus")
        IncidentStatus fromStatus,

        @JsonProperty("toStatus")
        IncidentStatus toStatus,

        @JsonProperty("changedBy")
        UUID changedBy,

        @JsonProperty("changeSource")
        String changeSource,

        @JsonProperty("comment")
        String comment,

        @JsonProperty("changedAt")
        Instant changedAt

) {
    public static IncidentHistoryDto from(IncidentHistory history) {
        return new IncidentHistoryDto(
                history.getId(),
                history.getFromStatus(),
                history.getToStatus(),
                history.getChangedBy(),
                history.getChangeSource(),
                history.getComment(),
                history.getChangedAt()
        );
    }
}