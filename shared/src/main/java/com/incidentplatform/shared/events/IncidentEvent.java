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
}
