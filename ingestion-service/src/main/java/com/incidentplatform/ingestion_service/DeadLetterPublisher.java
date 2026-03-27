package com.incidentplatform.ingestion_service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String deadLetterTopic;

    public DeadLetterPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${kafka.topics.alerts-dead-letter}") String deadLetterTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.deadLetterTopic = deadLetterTopic;
    }

    public void publish(JsonNode originalPayload,
                        String source,
                        String tenantId,
                        String errorReason) {
        try {
            final String dlqPayload = buildDlqPayload(
                    originalPayload, source, tenantId, errorReason);

            final String partitionKey = source + ":" + tenantId;

            kafkaTemplate.send(deadLetterTopic, partitionKey, dlqPayload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish to DLQ topic: {}, " +
                                            "source: {}, tenant: {}. " +
                                            "Original normalization error: {}. " +
                                            "Alert may be LOST — manual intervention required.",
                                    deadLetterTopic, source, tenantId,
                                    errorReason, ex);
                        } else {
                            log.info("Alert published to DLQ: topic={}, " +
                                            "partition={}, offset={}, source={}, " +
                                            "tenant={}, reason={}",
                                    deadLetterTopic,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset(),
                                    source, tenantId, errorReason);
                        }
                    });

        } catch (Exception e) {
            log.error("Unexpected error in DeadLetterPublisher, " +
                            "source: {}, tenant: {}, originalError: {}",
                    source, tenantId, errorReason, e);
        }
    }

    private String buildDlqPayload(JsonNode originalPayload,
                                   String source,
                                   String tenantId,
                                   String errorReason)
            throws JsonProcessingException {

        final ObjectNode dlqNode = objectMapper.createObjectNode();
        dlqNode.put("source", source);
        dlqNode.put("tenantId", tenantId);
        dlqNode.put("errorReason", errorReason);
        dlqNode.put("failedAt", Instant.now().toString());
        dlqNode.put("attemptCount", 1);

        dlqNode.set("originalPayload", originalPayload);

        return objectMapper.writeValueAsString(dlqNode);
    }
}