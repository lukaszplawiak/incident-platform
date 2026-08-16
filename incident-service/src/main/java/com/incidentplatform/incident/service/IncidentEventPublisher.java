package com.incidentplatform.incident.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.incident.domain.IncidentEventOutbox;
import com.incidentplatform.incident.repository.IncidentEventOutboxRepository;
import com.incidentplatform.shared.events.IncidentAcknowledgedEvent;
import com.incidentplatform.shared.events.IncidentClosedEvent;
import com.incidentplatform.shared.events.IncidentEscalatedEvent;
import com.incidentplatform.shared.events.IncidentEvent;
import com.incidentplatform.shared.events.IncidentEventTypes;
import com.incidentplatform.shared.events.IncidentOpenedEvent;
import com.incidentplatform.shared.events.IncidentResolvedEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds {@link IncidentEvent} records from {@link Incident} state and
 * stages them for publishing to {@code incidents.lifecycle}.
 *
 * <h2>Fixed (backlog #36): direct Kafka publish inside the caller's
 * open transaction</h2>
 * This class previously called {@code IncidentEventKafkaSender.send(...)}
 * directly — sending to Kafka (async, fire-and-forget) from inside
 * {@code IncidentCommandService}'s {@code @Transactional} methods, before
 * the enclosing database transaction had actually committed. If anything
 * later in the same transactional method caused a rollback, the Kafka
 * event had already been irreversibly sent — a "phantom event" describing
 * a database change that never actually happened, delivered to
 * escalation-service, notification-service, and postmortem-service.
 *
 * <p>Every {@code publishXxx} method here now writes a PENDING
 * {@link IncidentEventOutbox} row instead — via {@code outboxRepository},
 * which (since this class carries no {@code @Transactional} of its own)
 * participates in whatever transaction is already open in the calling
 * {@code IncidentCommandService} method, using Spring's default REQUIRED
 * propagation. The outbox row is therefore committed atomically with the
 * {@code Incident}/{@code IncidentHistory} change it describes — by
 * construction, it's impossible for an outbox row (and therefore a
 * published Kafka event) to exist for a database change that didn't
 * commit. {@code IncidentEventOutboxScheduler} publishes PENDING rows to
 * Kafka afterward, on its own dedicated scheduled thread.
 *
 * <p>Every public method's signature is unchanged — {@code
 * IncidentCommandService} required zero changes for this fix; the outbox
 * write is entirely internal to this class.
 *
 * <h2>Serialization failure now rolls back the transaction</h2>
 * Previously, a {@code JsonProcessingException} was logged and swallowed
 * — the business transaction (e.g. incident creation) still committed,
 * just with no event ever published, silently. Now it's wrapped in an
 * unchecked exception and allowed to propagate, rolling back the whole
 * transaction. {@link IncidentEvent} records only ever contain simple,
 * well-typed fields (UUIDs, enums, Strings, Instants) — a genuine
 * serialization failure here would indicate something is deeply wrong,
 * and failing loudly (the caller gets an error, nothing is silently
 * half-committed) is preferable to creating an incident that can never
 * be announced to the rest of the platform.
 */
@Component
public class IncidentEventPublisher {

    private final IncidentEventOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public IncidentEventPublisher(IncidentEventOutboxRepository outboxRepository,
                                  ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
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
        stage(incident.getId(), incident.getTenantId(),
                IncidentEventTypes.INCIDENT_OPENED, event);
    }

    public void publishAcknowledged(Incident incident, UUID acknowledgedBy) {
        final IncidentAcknowledgedEvent event = new IncidentAcknowledgedEvent(
                incident.getId(),
                incident.getTenantId(),
                acknowledgedBy,
                Instant.now()
        );
        stage(incident.getId(), incident.getTenantId(),
                IncidentEventTypes.INCIDENT_ACKNOWLEDGED, event);
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
        stage(incident.getId(), incident.getTenantId(),
                IncidentEventTypes.INCIDENT_RESOLVED, event);
    }

    public void publishClosed(Incident incident, UUID closedBy, UUID postmortemId) {
        final IncidentClosedEvent event = new IncidentClosedEvent(
                incident.getId(),
                incident.getTenantId(),
                closedBy,
                postmortemId,
                Instant.now()
        );
        stage(incident.getId(), incident.getTenantId(),
                IncidentEventTypes.INCIDENT_CLOSED, event);
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
        stage(incident.getId(), incident.getTenantId(),
                IncidentEventTypes.INCIDENT_ESCALATED, event);
    }

    /**
     * Serializes {@code event} and writes a PENDING outbox row — see this
     * class's Javadoc for the full account of why this replaces a direct
     * {@code IncidentEventKafkaSender.send(...)} call.
     */
    private void stage(UUID incidentId, String tenantId,
                       String eventType, IncidentEvent event) {
        final String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    String.format("Failed to serialize %s for outbox: incidentId=%s",
                            eventType, incidentId), e);
        }

        outboxRepository.save(IncidentEventOutbox.pending(
                incidentId, tenantId, eventType, payload));
    }
}