package com.incidentplatform.incident.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.shared.events.IncidentAcknowledgedEvent;
import com.incidentplatform.shared.events.IncidentClosedEvent;
import com.incidentplatform.shared.events.IncidentEscalatedEvent;
import com.incidentplatform.shared.events.IncidentOpenedEvent;
import com.incidentplatform.shared.events.IncidentResolvedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class IncidentEventPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String incidentsLifecycleTopic;

    public IncidentEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${kafka.topics.incidents-lifecycle}") String incidentsLifecycleTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.incidentsLifecycleTopic = incidentsLifecycleTopic;
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
        publish(incident.getId(), event, "IncidentOpenedEvent");
    }

    public void publishAcknowledged(Incident incident, UUID acknowledgedBy) {
        final IncidentAcknowledgedEvent event = new IncidentAcknowledgedEvent(
                incident.getId(),
                incident.getTenantId(),
                acknowledgedBy,
                Instant.now()
        );
        publish(incident.getId(), event, "IncidentAcknowledgedEvent");
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
        publish(incident.getId(), event, "IncidentResolvedEvent");
    }

    public void publishClosed(Incident incident, UUID closedBy, UUID postmortemId) {
        final IncidentClosedEvent event = new IncidentClosedEvent(
                incident.getId(),
                incident.getTenantId(),
                closedBy,
                postmortemId,
                Instant.now()
        );
        publish(incident.getId(), event, "IncidentClosedEvent");
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
        publish(incident.getId(), event, "IncidentEscalatedEvent");
    }

    private void publish(UUID incidentId, Object event, String eventType) {
        try {
            final String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(incidentsLifecycleTopic, incidentId.toString(), payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish {} to Kafka: " +
                                            "topic={}, incidentId={}",
                                    eventType, incidentsLifecycleTopic,
                                    incidentId, ex);
                        } else {
                            log.debug("{} published: topic={}, partition={}, " +
                                            "offset={}, incidentId={}",
                                    eventType, incidentsLifecycleTopic,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset(),
                                    incidentId);
                        }
                    });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize {}: incidentId={}",
                    eventType, incidentId, e);
        }
    }
}