package com.incidentplatform.shared.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Single source of truth for publishing {@link IncidentEvent}s to the
 * {@code incidents-lifecycle} topic.
 *
 * <p>Serializes the event, attaches the {@link IncidentEventTypes#HEADER_NAME}
 * Kafka header, sends with logging on success/failure, and uses
 * {@code incidentId} as the Kafka message key (preserves per-incident
 * ordering within a partition).
 *
 * <p>There are two producers on this topic: {@code IncidentEventPublisher}
 * (incident-service) and {@code EscalationScheduler} (escalation-service).
 * Before this class existed, each had its own send logic — EscalationScheduler's
 * version never set the {@code X-Event-Type} header, so escalation notifications
 * were silently dropped by notification-service's header-based consumer.
 * Consolidating to one class makes that class of bug structurally impossible:
 * the header is part of {@link #send}, not something every producer must
 * remember to add.
 */
@Component
public class IncidentEventKafkaSender {

    private static final Logger log =
            LoggerFactory.getLogger(IncidentEventKafkaSender.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String incidentsLifecycleTopic;

    public IncidentEventKafkaSender(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${kafka.topics.incidents-lifecycle}") String incidentsLifecycleTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.incidentsLifecycleTopic = incidentsLifecycleTopic;
    }

    /**
     * Serializes {@code event}, attaches the {@code X-Event-Type} header set
     * to {@code eventType}, and sends it to {@code incidents-lifecycle} keyed
     * by {@code event.incidentId()}.
     *
     * <p>Serialization failures are logged and swallowed — a malformed event
     * is a programming error in the producer, not something retrying would fix.
     * Kafka send failures are logged asynchronously via {@code whenComplete}.
     */
    public void send(IncidentEvent event, String eventType) {
        try {
            final String payload = objectMapper.writeValueAsString(event);

            final ProducerRecord<String, String> record = new ProducerRecord<>(
                    incidentsLifecycleTopic,
                    null,
                    event.incidentId().toString(),
                    payload
            );
            record.headers().add(new RecordHeader(
                    IncidentEventTypes.HEADER_NAME,
                    eventType.getBytes(StandardCharsets.UTF_8)
            ));

            kafkaTemplate.send(record)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish {} to Kafka: " +
                                            "topic={}, incidentId={}",
                                    eventType, incidentsLifecycleTopic,
                                    event.incidentId(), ex);
                        } else {
                            log.debug("{} published: topic={}, partition={}, " +
                                            "offset={}, incidentId={}",
                                    eventType, incidentsLifecycleTopic,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset(),
                                    event.incidentId());
                        }
                    });

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize {}: incidentId={}",
                    eventType, event.incidentId(), e);
        }
    }
}