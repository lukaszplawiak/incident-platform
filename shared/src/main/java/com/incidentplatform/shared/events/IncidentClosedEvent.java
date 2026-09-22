package com.incidentplatform.shared.events;

import java.time.Instant;
import java.util.UUID;

public record IncidentClosedEvent(
        UUID incidentId,
        String tenantId,
        UUID closedBy,
        UUID postmortemId,
        Instant occurredAt,
        UUID teamId
) implements IncidentEvent {

    public IncidentClosedEvent {
        if (occurredAt == null) occurredAt = Instant.now();
    }
}
