package com.incidentplatform.incident.dto;

import com.incidentplatform.incident.domain.AuditEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEventDto(
        UUID id,
        UUID incidentId,
        String eventType,
        String actor,
        String actorType,
        String sourceService,
        String detail,
        Map<String, Object> metadata,
        Instant occurredAt
) {
    public static AuditEventDto from(AuditEvent event) {
        return new AuditEventDto(
                event.getId(),
                event.getIncidentId(),
                event.getEventType(),
                event.getActor(),
                event.getActorType(),
                event.getSourceService(),
                event.getDetail(),
                event.getMetadata(),
                event.getOccurredAt()
        );
    }
}