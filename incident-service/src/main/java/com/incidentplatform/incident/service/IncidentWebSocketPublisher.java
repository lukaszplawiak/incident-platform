package com.incidentplatform.incident.service;

import com.incidentplatform.incident.dto.IncidentDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class IncidentWebSocketPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentWebSocketPublisher.class);

    private static final String INCIDENTS_TOPIC_PREFIX = "/topic/incidents/";

    private final SimpMessagingTemplate messagingTemplate;

    public IncidentWebSocketPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Publishes a generic update for an incident whose status did NOT change
     * (e.g. severity update on a duplicate alert, assignee change). Uses the
     * {@code INCIDENT_UPDATED} event type so frontend clients can distinguish
     * "this incident's data changed" from {@link #publishCreated} (new row)
     * and {@link #publishStatusChanged} (status transition, different toast
     * copy). Previously this method sent the raw {@link IncidentDto} without
     * any {@code eventType} wrapper — inconsistent with the other two publish
     * methods and unrecognized by the frontend's event switch, so updates
     * silently failed to refresh the UI.
     */
    public void publishUpdate(IncidentDto incident) {
        final String topic = INCIDENTS_TOPIC_PREFIX + incident.tenantId();

        try {
            final WebSocketMessage message = new WebSocketMessage(
                    "INCIDENT_UPDATED", incident);
            messagingTemplate.convertAndSend(topic, message);

            log.debug("WebSocket UPDATED published: topic={}, incidentId={}, " +
                            "status={}, tenant={}",
                    topic, incident.id(), incident.status(), incident.tenantId());

        } catch (Exception e) {
            log.error("Failed to publish WebSocket UPDATED: topic={}, " +
                            "incidentId={}, tenant={}",
                    topic, incident.id(), incident.tenantId(), e);
        }
    }

    public void publishCreated(IncidentDto incident) {
        final String topic = INCIDENTS_TOPIC_PREFIX + incident.tenantId();

        try {
            final WebSocketMessage message = new WebSocketMessage(
                    "INCIDENT_CREATED", incident);
            messagingTemplate.convertAndSend(topic, message);

            log.info("WebSocket CREATED published: topic={}, incidentId={}, " +
                            "severity={}, tenant={}",
                    topic, incident.id(), incident.severity(), incident.tenantId());

        } catch (Exception e) {
            log.error("Failed to publish WebSocket CREATED: topic={}, " +
                    "incidentId={}", topic, incident.id(), e);
        }
    }

    public void publishStatusChanged(IncidentDto incident, String previousStatus) {
        final String topic = INCIDENTS_TOPIC_PREFIX + incident.tenantId();

        try {
            final WebSocketMessage message = new WebSocketMessage(
                    "INCIDENT_STATUS_CHANGED", incident, previousStatus);
            messagingTemplate.convertAndSend(topic, message);

            log.info("WebSocket STATUS_CHANGED published: topic={}, " +
                            "incidentId={}, {} → {}, tenant={}",
                    topic, incident.id(), previousStatus,
                    incident.status(), incident.tenantId());

        } catch (Exception e) {
            log.error("Failed to publish WebSocket STATUS_CHANGED: topic={}, " +
                    "incidentId={}", topic, incident.id(), e);
        }
    }

    public record WebSocketMessage(
            String eventType,
            IncidentDto incident,
            String metadata
    ) {
        public WebSocketMessage(String eventType, IncidentDto incident) {
            this(eventType, incident, null);
        }
    }
}