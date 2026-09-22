package com.incidentplatform.shared.events;

import java.time.Instant;
import java.util.UUID;


public sealed interface IncidentEvent
        permits IncidentOpenedEvent,
        IncidentAcknowledgedEvent,
        IncidentResolvedEvent,
        IncidentEscalatedEvent,
        IncidentClosedEvent {

    UUID incidentId();

    String tenantId();

    Instant occurredAt();

    /**
     * The team the incident belongs to, or {@code null} for an incident with no team
     * assignment (manually created, or an {@code Integration} without a team). Added by
     * backlog #0-12 so a typed consumer can scope routing to the incident's team instead of
     * hand-parsing {@code teamId} out of the raw Kafka JSON, as escalation-service and
     * notification-service both did before this field existed on the record.
     */
    UUID teamId();
}
