package com.incidentplatform.shared.events;

import com.incidentplatform.shared.domain.Severity;

import java.time.Instant;
import java.util.UUID;

public record IncidentOpenedEvent(
        UUID incidentId,
        String tenantId,
        UUID alertId,
        String fingerprint,
        String title,
        Severity severity,
        SourceType sourceType,
        Instant occurredAt
) implements IncidentEvent {
    public IncidentOpenedEvent {
        if (occurredAt == null) occurredAt = Instant.now();
    }
}