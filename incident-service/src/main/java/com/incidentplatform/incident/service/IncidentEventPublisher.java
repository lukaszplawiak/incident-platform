package com.incidentplatform.incident.service;

import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.shared.events.IncidentAcknowledgedEvent;
import com.incidentplatform.shared.events.IncidentClosedEvent;
import com.incidentplatform.shared.events.IncidentEscalatedEvent;
import com.incidentplatform.shared.events.IncidentEventKafkaSender;
import com.incidentplatform.shared.events.IncidentEventTypes;
import com.incidentplatform.shared.events.IncidentOpenedEvent;
import com.incidentplatform.shared.events.IncidentResolvedEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds {@link com.incidentplatform.shared.events.IncidentEvent} records from
 * {@link Incident} state and publishes them via {@link IncidentEventKafkaSender}.
 *
 * <p>Serialization, Kafka headers and send logging are handled by
 * {@link IncidentEventKafkaSender} — this class is only responsible for
 * mapping domain state to the correct event type.
 */
@Component
public class IncidentEventPublisher {

    private final IncidentEventKafkaSender kafkaSender;

    public IncidentEventPublisher(IncidentEventKafkaSender kafkaSender) {
        this.kafkaSender = kafkaSender;
    }

    public void publishOpened(Incident incident) {
        final IncidentOpenedEvent event = new IncidentOpenedEvent(
                incident.getId(),
                incident.getTenantId(),
                incident.getAlertId(),
                incident.getAlertFingerprint(),
                incident.getTitle(),
                incident.getSeverity(),
                incident.getSourceType(),
                Instant.now()
        );
        kafkaSender.send(event, IncidentEventTypes.INCIDENT_OPENED);
    }

    public void publishAcknowledged(Incident incident, UUID acknowledgedBy) {
        final IncidentAcknowledgedEvent event = new IncidentAcknowledgedEvent(
                incident.getId(),
                incident.getTenantId(),
                acknowledgedBy,
                Instant.now()
        );
        kafkaSender.send(event, IncidentEventTypes.INCIDENT_ACKNOWLEDGED);
    }

    public void publishResolved(Incident incident, UUID resolvedBy) {
        final long durationMinutes = incident.getMttrMinutes() != null
                ? incident.getMttrMinutes()
                : 0L;

        final IncidentResolvedEvent event = new IncidentResolvedEvent(
                incident.getId(),
                incident.getTenantId(),
                resolvedBy,
                incident.getAlertFingerprint(),
                durationMinutes,
                null,
                incident.getTitle(),
                incident.getSeverity(),
                Instant.now()
        );
        kafkaSender.send(event, IncidentEventTypes.INCIDENT_RESOLVED);
    }

    public void publishClosed(Incident incident, UUID closedBy, UUID postmortemId) {
        final IncidentClosedEvent event = new IncidentClosedEvent(
                incident.getId(),
                incident.getTenantId(),
                closedBy,
                postmortemId,
                Instant.now()
        );
        kafkaSender.send(event, IncidentEventTypes.INCIDENT_CLOSED);
    }

    public void publishEscalated(Incident incident,
                                 UUID escalateTo,
                                 int escalationLevel) {
        final IncidentEscalatedEvent event = new IncidentEscalatedEvent(
                incident.getId(),
                incident.getTenantId(),
                escalateTo,
                escalationLevel,
                incident.getSeverity(),
                incident.getTitle(),
                Instant.now()
        );
        kafkaSender.send(event, IncidentEventTypes.INCIDENT_ESCALATED);
    }
}