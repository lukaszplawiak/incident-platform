package com.incidentplatform.shared.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;

/**
 * Publishes unprocessable Kafka messages to a dead-letter topic.
 *
 * <p>Used when a message is permanently malformed (poison pill) and retrying
 * would never succeed. Instead of silently dropping the message or blocking
 * the partition indefinitely, it is forwarded to a dedicated dead-letter topic
 * for investigation and potential manual reprocessing.
 *
 * <p>This class is NOT a Spring {@code @Component} — it is instantiated by
 * each service's {@code KafkaConfig} with the dead-letter topic name specific
 * to that service. This avoids a shared {@code @Value} property name clash
 * across services that use different DLT topic names:
 * <ul>
 *   <li>ingestion-service → {@code alerts.dead-letter}
 *   <li>incident-service  → {@code incidents.dead-letter}
 * </ul>
 *
 * <p>DLT message format:
 * <pre>{@code
 * {
 *   "sourceService":   "incident-service",
 *   "sourceTopic":     "alerts.raw",
 *   "tenantId":        "acme-corp",
 *   "errorReason":     "Failed to deserialize ...",
 *   "failedAt":        "2026-06-07T12:00:00Z",
 *   "originalPayload": "<raw string from Kafka>"
 * }
 * }</pre>
 */
public class DeadLetterPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(DeadLetterPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String deadLetterTopic;
    private final String sourceService;

    public DeadLetterPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper objectMapper,
                               String deadLetterTopic,
                               String sourceService) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.deadLetterTopic = deadLetterTopic;
        this.sourceService = sourceService;
    }

    /**
     * Publishes a raw string payload to the dead-letter topic.
     * Used by Kafka consumers that receive {@code ConsumerRecord<String, String>}.
     */
    public void publish(String originalPayload,
                        String sourceTopic,
                        String tenantId,
                        String errorReason) {
        doPublish(originalPayload, sourceTopic, tenantId, errorReason);
    }

    /**
     * Publishes an arbitrary object to the dead-letter topic by serializing it to JSON.
     * Used by ingestion-service where the payload is a {@code JsonNode}.
     */
    public void publish(Object originalPayload,
                        String sourceTopic,
                        String tenantId,
                        String errorReason) {
        try {
            final String serialized = originalPayload instanceof String s
                    ? s
                    : objectMapper.writeValueAsString(originalPayload);
            doPublish(serialized, sourceTopic, tenantId, errorReason);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize originalPayload for DLT — " +
                    "publishing raw toString() instead: {}", e.getMessage());
            doPublish(String.valueOf(originalPayload),
                    sourceTopic, tenantId, errorReason);
        }
    }

    private void doPublish(String originalPayload,
                           String sourceTopic,
                           String tenantId,
                           String errorReason) {
        try {
            final String dltPayload = buildDltPayload(
                    originalPayload, sourceTopic, tenantId, errorReason);

            final String partitionKey = sourceService + ":" + tenantId;

            kafkaTemplate.send(deadLetterTopic, partitionKey, dltPayload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish to DLT: topic={}, " +
                                            "sourceTopic={}, tenant={}, reason={}. " +
                                            "Message may be LOST — manual intervention required.",
                                    deadLetterTopic, sourceTopic,
                                    tenantId, errorReason, ex);
                        } else {
                            log.info("Message published to DLT: topic={}, " +
                                            "partition={}, offset={}, sourceTopic={}, " +
                                            "tenant={}, reason={}",
                                    deadLetterTopic,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset(),
                                    sourceTopic, tenantId, errorReason);
                        }
                    });

        } catch (Exception e) {
            log.error("Unexpected error in DeadLetterPublisher: " +
                            "sourceTopic={}, tenant={}, originalError={}",
                    sourceTopic, tenantId, errorReason, e);
        }
    }

    private String buildDltPayload(String originalPayload,
                                   String sourceTopic,
                                   String tenantId,
                                   String errorReason)
            throws JsonProcessingException {
        final ObjectNode node = objectMapper.createObjectNode();
        node.put("sourceService", sourceService);
        node.put("sourceTopic", sourceTopic);
        node.put("tenantId", tenantId);
        node.put("errorReason", errorReason);
        node.put("failedAt", Instant.now().toString());
        node.put("originalPayload", originalPayload);
        return objectMapper.writeValueAsString(node);
    }
}