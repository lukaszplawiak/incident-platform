package com.incidentplatform.incident.domain;

import com.incidentplatform.shared.audit.AuditEventTypes;

/**
 * Main lifecycle status of an incident.
 *
 * <p>Escalation is tracked separately via {@link Incident#getEscalationLevel()}
 * — it is an independent attribute (how urgently this incident needs
 * attention), not a state in this lifecycle. An incident can be escalated
 * while {@code ACKNOWLEDGED} (waiting for resolution) without that fact
 * blocking or competing with its primary status transitions.
 *
 * <p>Each status carries its own {@link #auditEventType()} so that mapping
 * a status to its audit log event type doesn't require a separate switch
 * statement in the service layer — one fewer place to keep in sync when a
 * status is added, removed, or its audit semantics change.
 */
public enum IncidentStatus {

    OPEN(AuditEventTypes.INCIDENT_STATUS_CHANGED),

    ACKNOWLEDGED(AuditEventTypes.INCIDENT_ACKNOWLEDGED),

    RESOLVED(AuditEventTypes.INCIDENT_RESOLVED),

    CLOSED(AuditEventTypes.INCIDENT_CLOSED);

    private final String auditEventType;

    IncidentStatus(String auditEventType) {
        this.auditEventType = auditEventType;
    }

    public String auditEventType() {
        return auditEventType;
    }
}