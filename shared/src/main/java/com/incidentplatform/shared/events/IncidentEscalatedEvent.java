package com.incidentplatform.shared.events;

import com.incidentplatform.shared.domain.Severity;

import java.time.Instant;
import java.util.UUID;

public record IncidentEscalatedEvent(
        UUID incidentId,
        String tenantId,
        UUID escalateTo,
        int escalationLevel,
        Severity severity,
        String title,
        Instant occurredAt,
        UUID teamId
) implements IncidentEvent {

    public IncidentEscalatedEvent {
        if (occurredAt == null) occurredAt = Instant.now();
    }
}
