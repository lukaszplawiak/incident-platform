package com.incidentplatform.shared.events;

import java.time.Instant;
import java.util.UUID;

public record IncidentAcknowledgedEvent(
        UUID incidentId,
        String tenantId,
        UUID acknowledgedBy,
        Instant occurredAt
) implements IncidentEvent {

    public IncidentAcknowledgedEvent {
        if (occurredAt == null) occurredAt = Instant.now();
    }
}
