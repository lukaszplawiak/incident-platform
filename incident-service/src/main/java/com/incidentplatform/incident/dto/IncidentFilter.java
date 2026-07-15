package com.incidentplatform.incident.dto;

import com.incidentplatform.incident.domain.IncidentStatus;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.SourceType;

import java.util.UUID;

public record IncidentFilter(

        IncidentStatus status,

        Severity severity,

        SourceType sourceType,

        String source,

        /**
         * Filter by team assignment. {@code null} = no filter (show all).
         * Pass a team UUID to show only incidents assigned to that team.
         *
         * <p>Used by team members to see only their team's incidents:
         * {@code GET /api/v1/incidents?teamId={teamId}}
         *
         * <p>Note: tenantId isolation is always enforced regardless of this
         * filter — users can only see incidents in their own tenant.
         */
        UUID teamId

) {
    public boolean hasAnyFilter() {
        return status != null || severity != null
                || sourceType != null || source != null
                || teamId != null;
    }
}