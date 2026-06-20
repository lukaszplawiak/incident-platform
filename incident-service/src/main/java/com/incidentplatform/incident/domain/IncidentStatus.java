package com.incidentplatform.incident.domain;

/**
 * Main lifecycle status of an incident.
 *
 * <p>Escalation is tracked separately via {@link Incident#getEscalationLevel()}
 * — it is an independent attribute (how urgently this incident needs
 * attention), not a state in this lifecycle. An incident can be escalated
 * while {@code ACKNOWLEDGED} (waiting for resolution) without that fact
 * blocking or competing with its primary status transitions.
 */
public enum IncidentStatus {

    OPEN,

    ACKNOWLEDGED,

    RESOLVED,

    CLOSED
}