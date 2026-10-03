package com.incidentplatform.shared.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.shared.security.TenantIds;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

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
 * <h2>Tenant resolution (changed in backlog #0-92)</h2>
 * The payload's {@code tenantId} is the record's tenant; the
 * {@code X-Tenant-Id} header is a copy of it, written by the sender through
 * {@link TenantRecords} for code that reads a record without parsing it.
 * <ol>
 *   <li>The payload must name a {@link com.incidentplatform.shared.security.TenantIds valid tenant id}.
 *   <li>The header must be present, valid and equal to the payload's tenant.
 *   <li>Anything else is a {@link TenantResolutionException} (an
 *       {@code IllegalArgumentException}, so the consumer's poison-pill path
 *       dead-letters it), counted in {@code kafka.records.tenant.rejected}
 *       (tag {@code reason}, every reason registered at zero).
 * </ol>
 * It used to take the header first, checked for nothing but blankness, and
 * the payload only when the header was missing, without comparing them: a
 * header could carry line breaks into every log line of the record's
 * processing, or name another tenant than the record's own data (#0-92). No
 * message or log line quotes either value.
 *
 * <p>A missing header was first accepted (logged, the record trusted on its
 * payload alone, as records carried their tenant only in the payload before
 * the header existed). Every sender now writes it ({@link TenantRecords}) and
 * no record from before is kept (topics and database start empty), so a
 * record without it was not built by the platform's code: refused like any
 * other ({@code reason=header_missing}), which also ends a warning per record
 * a producer could flood the logs with (found in review).
 *
 * <p>See {@link TenantKafkaConsumerInterceptor}'s own Javadoc for how this
 * fits into the platform's overall division of tenant-handling
 * responsibility across the poll thread, the listener thread, and each
 * individual consumer.
 */
@Component
public class TenantKafkaRecordResolver {

    static final String REJECTED_COUNTER = "kafka.records.tenant.rejected";

    private final ObjectMapper objectMapper;
    private final Map<TenantResolutionException.Reason, Counter> rejected =
            new EnumMap<>(TenantResolutionException.Reason.class);

    public TenantKafkaRecordResolver(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        for (final TenantResolutionException.Reason reason : TenantResolutionException.Reason.values()) {
            rejected.put(reason, Counter.builder(REJECTED_COUNTER)
                    .description("Consumed records whose tenant was refused and dead-lettered (backlog #0-92)")
                    .tag("reason", reason.tag())
                    .register(meterRegistry));
        }
    }

    /**
     * @throws IllegalArgumentException for a payload that is not JSON; the
     *         message names the problem, not the payload (Jackson's own message
     *         quotes it), and the parser's exception is the cause
     */
    public JsonNode parseJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (IOException e) {
            throw new UnreadableRecordException("Unparseable JSON payload ("
                    + e.getClass().getSimpleName() + ")", e);
        }
    }

    /**
     * The record's tenant: the payload's, checked against its header (see the
     * class Javadoc).
     *
     * @throws TenantResolutionException when the tenant cannot be trusted
     */
    public String extractTenantId(ConsumerRecord<?, ?> record, JsonNode payload) {
        final Refusal refusal = check(record, payload);
        if (refusal != null) {
            throw refuse(refusal.reason(), record, refusal.what());
        }
        return payload.path("tenantId").asText();
    }

    /**
     * The record's tenant by the same rule as {@link #extractTenantId}, or
     * {@code null} when it cannot be trusted or the payload is not JSON:
     * neither counted nor thrown. For a record already refused for another
     * reason (a missing {@code X-Event-Type}, backlog #0-96, found in review),
     * whose dead-letter copy should still name its tenant when it has a
     * trustworthy one; counting it here would report a tenant refusal for a
     * record refused for something else.
     */
    public String trustedTenantOrNull(ConsumerRecord<?, String> record) {
        final JsonNode payload;
        try {
            payload = objectMapper.readTree(record.value());
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        return payload != null && check(record, payload) == null ? payload.path("tenantId").asText() : null;
    }

    private record Refusal(TenantResolutionException.Reason reason, String what) {
    }

    private static Refusal check(ConsumerRecord<?, ?> record, JsonNode payload) {
        final JsonNode field = payload.path("tenantId");
        final String payloadTenantId = field.isTextual() ? field.asText() : null;
        if (payloadTenantId == null || payloadTenantId.isBlank()) {
            return new Refusal(TenantResolutionException.Reason.MISSING, "the payload names no tenantId");
        }
        if (!TenantIds.isValid(payloadTenantId)) {
            return new Refusal(TenantResolutionException.Reason.INVALID,
                    "the payload's tenantId is not a valid tenant id");
        }

        final Header header = record.headers()
                .lastHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER);
        if (header == null) {
            return new Refusal(TenantResolutionException.Reason.HEADER_MISSING, "the X-Tenant-Id header is missing");
        }
        final String headerTenantId = new String(header.value(), StandardCharsets.UTF_8);
        if (!TenantIds.isValid(headerTenantId)) {
            return new Refusal(TenantResolutionException.Reason.INVALID,
                    "the X-Tenant-Id header is not a valid tenant id");
        }
        if (!headerTenantId.equals(payloadTenantId)) {
            return new Refusal(TenantResolutionException.Reason.MISMATCH,
                    "the X-Tenant-Id header names another tenant than the payload");
        }
        return null;
    }

    private TenantResolutionException refuse(TenantResolutionException.Reason reason,
                                              ConsumerRecord<?, ?> record, String what) {
        rejected.get(reason).increment();
        return new TenantResolutionException(reason, "Record tenant refused (" + what + "): topic="
                + record.topic() + ", partition=" + record.partition() + ", offset=" + record.offset());
    }
}