package com.incidentplatform.shared.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Parses a Kafka record's JSON payload and resolves its tenant, consistently
 * across every {@code @KafkaListener} consumer in the platform.
 *
 * <h2>Fixed (backlog #75): platform-wide duplication</h2>
 * {@code parseJson}/{@code extractTenantId} were previously private methods,
 * byte-for-byte identical, independently copy-pasted into five separate
 * {@code @KafkaListener} consumer classes across four services
 * ({@code incident-service}'s {@code IncidentKafkaConsumer} and
 * {@code IncidentEscalationEventConsumer}, and each of
 * {@code escalation-service}, {@code notification-service}, and
 * {@code postmortem-service}'s own {@code IncidentEventConsumer}). Extracted
 * here so the tenant-resolution contract — header first, payload fallback,
 * poison pill if both are absent — lives in exactly one place.
 *
 * <h2>Why an injected {@code @Component}, not a static utility class</h2>
 * Every other piece of logic shared across services in this codebase that
 * doesn't need per-service configuration ({@link com.incidentplatform.shared.audit.AuditEventPublisher},
 * for one) is an injected Spring bean, constructor-wired like any other
 * collaborator — not a static utility class. {@code DeadLetterPublisher}
 * (this same package) is the one exception, and deliberately so: it needs a
 * service-specific dead-letter topic name Spring's component scanning can't
 * supply on its own, so each service's own {@code KafkaConfig} constructs it
 * manually. This class needs no such per-service parameterization — just the
 * application's own, already-configured {@link ObjectMapper} bean — so
 * there's no reason to break from the established, consistent pattern the
 * rest of this codebase already uses for shared logic like this.
 *
 * <h2>Tenant resolution strategy</h2>
 * <ol>
 *   <li><b>Header</b> — reads {@code X-Tenant-Id} set by
 *       {@link TenantKafkaProducerInterceptor} (fast path, no deserialization needed).
 *   <li><b>Payload</b> — falls back to the {@code tenantId} field in the JSON body.
 *       This covers replay scenarios, manual publishes, or messages produced by a
 *       non-standard producer that skipped the interceptor.
 *   <li><b>Poison pill</b> — if absent in both, throws {@link IllegalArgumentException}
 *       so the caller's own catch block can route the record to its dead-letter topic.
 * </ol>
 *
 * <p>See {@link TenantKafkaConsumerInterceptor}'s own Javadoc for how this
 * fits into the platform's overall division of tenant-handling
 * responsibility across the poll thread, the listener thread, and each
 * individual consumer.
 */
@Component
public class TenantKafkaRecordResolver {

    private static final Logger log =
            LoggerFactory.getLogger(TenantKafkaRecordResolver.class);

    private final ObjectMapper objectMapper;

    public TenantKafkaRecordResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode parseJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Unparseable JSON payload: " + e.getMessage(), e);
        }
    }

    public String extractTenantId(ConsumerRecord<?, ?> record, JsonNode payload) {
        // Step 1 — Kafka header (set by TenantKafkaProducerInterceptor)
        final Header header = record.headers()
                .lastHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER);
        if (header != null) {
            final String tenantId = new String(header.value(), StandardCharsets.UTF_8);
            if (!tenantId.isBlank()) {
                return tenantId;
            }
        }

        // Step 2 — payload field (fallback for replay / non-interceptor producers)
        final String payloadTenantId = payload.path("tenantId").asText(null);
        if (payloadTenantId != null && !payloadTenantId.isBlank()) {
            log.warn("X-Tenant-Id header missing — resolved tenantId from payload: " +
                            "topic={}, partition={}, offset={}, tenantId={}",
                    record.topic(), record.partition(), record.offset(), payloadTenantId);
            return payloadTenantId;
        }

        // Step 3 — poison pill: tenantId absent in both header and payload
        throw new IllegalArgumentException(
                "Missing tenantId in both X-Tenant-Id header and payload.tenantId: " +
                        "topic=" + record.topic() +
                        ", partition=" + record.partition() +
                        ", offset=" + record.offset());
    }
}