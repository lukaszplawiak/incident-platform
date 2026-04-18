package com.incidentplatform.shared.events;

import java.time.Instant;
import java.util.UUID;

public record IncidentResolvedEvent(
        UUID incidentId,
        String tenantId,
        UUID resolvedBy,
        String fingerprint,
        long durationMinutes,
        String resolution,
        Instant occurredAt
) implements IncidentEvent {
    public IncidentResolvedEvent {
        if (occurredAt == null) occurredAt = Instant.now();
    }
}