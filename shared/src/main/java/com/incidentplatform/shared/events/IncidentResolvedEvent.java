package com.incidentplatform.shared.events;

import com.incidentplatform.shared.domain.Severity;

import java.time.Instant;
import java.util.UUID;

public record IncidentResolvedEvent(
        UUID incidentId,
        String tenantId,
        UUID resolvedBy,
        String fingerprint,
        long durationMinutes,
        String resolution,
        Severity severity,
        Instant occurredAt
) implements IncidentEvent {
    public IncidentResolvedEvent {
        if (occurredAt == null) occurredAt = Instant.now();
    }
}