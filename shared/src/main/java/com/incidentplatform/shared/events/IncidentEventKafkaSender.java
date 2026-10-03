package com.incidentplatform.shared.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.shared.kafka.TenantRecords;
import com.incidentplatform.shared.security.TenantIds;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 *
 * <h2>Fixed (backlog #0-91): the tenant header is set here</h2>
 * Incident events carried no {@code X-Tenant-Id} of their own: the header came
 * from {@code TenantKafkaProducerInterceptor}, which read the thread's
 * {@code TenantContext}, so it was there only because both senders (the
 * outbox scheduler, the escalation scheduler) happened to set that context
 * first. Records are now built by {@link TenantRecords} from the event's own
 * tenant. The key stays the incident id: it keeps one incident's events in
 * order on one partition.
 *
 * <h2>Added for backlog #36: {@link #sendRawSync}</h2>
 * incident-service's {@code IncidentEventOutboxScheduler} needs to block
 * until the broker actually confirms the send (or definitively fails)
 * before deciding whether to mark an outbox entry PUBLISHED — unlike
 * {@link #send}'s async, fire-and-forget behavior, appropriate for its
 * direct in-transaction callers where blocking on the broker would add
 * request latency for no correctness benefit. {@link #send}'s existing
 * signature and async behavior are unchanged — escalation-service's
 * {@code EscalationScheduler} still calls it exactly as before; the new
 * method is purely additive. Both share {@link #buildRecord} so the
 * actual {@code ProducerRecord} construction (topic, key, header) has
 * exactly one implementation regardless of which send path is used.
 */
@Component
@ConditionalOnProperty(name = "kafka.topics.incidents-lifecycle")
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
            final ProducerRecord<String, String> record =
                    buildRecord(event.incidentId().toString(), event.tenantId(), eventType, payload);

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

    /**
     * Synchronous variant for {@code incident-service}'s
     * {@code IncidentEventOutboxScheduler} — blocks until the broker
     * acknowledges the send (or {@code timeout} elapses / the send fails),
     * so the caller can definitively know whether to mark its outbox entry
     * PUBLISHED or leave it PENDING for the next poll. Safe to block here:
     * the outbox scheduler runs on its own dedicated scheduled thread, not
     * an HTTP request thread — there is no user-facing latency to protect,
     * unlike {@link #send}'s direct in-transaction callers.
     *
     * <p>Unlike {@link #send}, this method takes an already-serialized
     * JSON {@code payload} rather than a typed {@link IncidentEvent} —
     * the outbox scheduler reads that JSON back from the
     * {@code incident_event_outbox} table exactly as it was written at
     * outbox-entry-creation time, rather than deserializing and
     * re-serializing it (which would risk the payload subtly changing
     * shape if the event's Java record definition evolves between write
     * and publish).
     *
     * @param tenantId the tenant of the event in {@code jsonPayload} (its outbox
     *                 row's), for the record's tenant header (backlog #0-91)
     * @throws IllegalArgumentException if {@code tenantId} is not a valid tenant
     *         id, or is not the payload's own {@code tenantId}: every consumer
     *         would refuse that record as a mismatch and dead-letter it, so it
     *         is refused here, as the row's failed attempt, before it is sent
     *         (found in review); neither value is quoted
     * @throws java.util.concurrent.ExecutionException if the send itself failed
     * @throws InterruptedException if the calling thread was interrupted while waiting
     * @throws java.util.concurrent.TimeoutException if the broker didn't acknowledge within {@code timeout}
     */
    public void sendRawSync(String incidentId, String tenantId, String eventType, String jsonPayload,
                            java.time.Duration timeout)
            throws java.util.concurrent.ExecutionException, InterruptedException,
            java.util.concurrent.TimeoutException {
        requirePayloadTenant(tenantId, jsonPayload);
        final ProducerRecord<String, String> record =
                buildRecord(incidentId, tenantId, eventType, jsonPayload);

        kafkaTemplate.send(record)
                .get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

        log.debug("{} published synchronously: topic={}, incidentId={}",
                eventType, incidentsLifecycleTopic, incidentId);
    }

    private void requirePayloadTenant(String tenantId, String jsonPayload) {
        TenantIds.requireValid(tenantId);
        final JsonNode payloadTenant;
        try {
            payloadTenant = objectMapper.readTree(jsonPayload).path("tenantId");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Outbox payload is not JSON ("
                    + e.getClass().getSimpleName() + ")", e);
        }
        if (!payloadTenant.isTextual() || !payloadTenant.asText().equals(tenantId)) {
            throw new IllegalArgumentException(
                    "Outbox row's tenant is not its payload's tenantId; not sent");
        }
    }

    private ProducerRecord<String, String> buildRecord(
            String incidentId, String tenantId, String eventType, String payload) {
        final ProducerRecord<String, String> record =
                TenantRecords.forTenant(incidentsLifecycleTopic, incidentId, payload, tenantId);
        record.headers().add(new RecordHeader(
                IncidentEventTypes.HEADER_NAME,
                eventType.getBytes(StandardCharsets.UTF_8)
        ));
        return record;
    }
}