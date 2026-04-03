package com.incidentplatform.incident.dto;

import com.incidentplatform.incident.domain.IncidentStatus;
import com.incidentplatform.shared.events.SourceType;

public record IncidentFilter(

        IncidentStatus status,

        String severity,

        SourceType sourceType,

        String source

) {
    public boolean hasAnyFilter() {
        return status != null || severity != null
                || sourceType != null || source != null;
    }
}