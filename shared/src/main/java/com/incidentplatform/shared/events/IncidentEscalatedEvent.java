package com.incidentplatform.shared.events;

import java.time.Instant;
import java.util.UUID;

public record IncidentEscalatedEvent(
        UUID incidentId,
        String tenantId,
        UUID escalateTo,
        int escalationLevel,
        String severity,
        String title,
        Instant occurredAt
) implements IncidentEvent {

    public IncidentEscalatedEvent {
        if (occurredAt == null) occurredAt = Instant.now();
    }
}
