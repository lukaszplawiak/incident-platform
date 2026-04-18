package com.incidentplatform.shared.events;

import java.time.Instant;
import java.util.UUID;

public record IncidentOpenedEvent(
        UUID incidentId,
        String tenantId,
        UUID alertId,
        String fingerprint,
        String title,
        String severity,
        SourceType sourceType,
        Instant occurredAt
) implements IncidentEvent {
    public IncidentOpenedEvent {
        if (occurredAt == null) occurredAt = Instant.now();
    }
}