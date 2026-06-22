package com.incidentplatform.shared.dto;

import com.incidentplatform.shared.audit.ActorType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEventMessage(
        UUID incidentId,
        String tenantId,
        String eventType,
        String actor,
        ActorType actorType,
        String sourceService,
        String detail,
        Map<String, Object> metadata,
        Instant occurredAt
) {
    public static AuditEventMessage system(UUID incidentId,
                                           String tenantId,
                                           String eventType,
                                           String sourceService,
                                           String detail,
                                           Map<String, Object> metadata) {
        return new AuditEventMessage(
                incidentId, tenantId, eventType,
                sourceService, ActorType.SYSTEM,
                sourceService, detail, metadata,
                Instant.now());
    }

    public static AuditEventMessage user(UUID incidentId,
                                         String tenantId,
                                         String eventType,
                                         String sourceService,
                                         String userId,
                                         String detail,
                                         Map<String, Object> metadata) {
        return new AuditEventMessage(
                incidentId, tenantId, eventType,
                userId, ActorType.USER,
                sourceService, detail, metadata,
                Instant.now());
    }
}