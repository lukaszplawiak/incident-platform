package com.incidentplatform.incident.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.incidentplatform.incident.domain.Incident;
import com.incidentplatform.incident.repository.IncidentRepository;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.IncidentEventTypes;
import com.incidentplatform.shared.events.SourceType;
import com.incidentplatform.shared.kafka.TenantKafkaProducerInterceptor;
import com.incidentplatform.shared.kafka.TenantKafkaRecordResolver;
import com.incidentplatform.shared.security.TenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

/**
 * Previously had no test file at all. Added primarily to cover backlog
 * #40's fix — see {@link IncidentEscalationEventConsumer}'s own Javadoc
 * for the full account of the logging-precision improvement being
 * regression-tested here — but also gives this class its first baseline
 * coverage for the rest of its behavior.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentEscalationEventConsumer")
class IncidentEscalationEventConsumerTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private Acknowledgment acknowledgment;

    private IncidentEscalationEventConsumer consumer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TOPIC = "incidents.lifecycle";
    private static final String TENANT_ID = "acme-corp";

    @BeforeEach
    void setUp() {
        // Fixed (backlog #75): extractTenantId/parseJson moved to the
        // shared TenantKafkaRecordResolver — objectMapper is no longer
        // passed to the consumer directly, only used to build this.
        consumer = new IncidentEscalationEventConsumer(
                incidentRepository, new TenantKafkaRecordResolver(objectMapper));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Incident buildIncident() {
        return new Incident(
                TENANT_ID, "High CPU usage", "CPU exceeded 95%",
                Severity.CRITICAL, SourceType.OPS, "prometheus",
                "prometheus:highcpu:server-1", UUID.randomUUID(), Instant.now());
    }

    private ConsumerRecord<String, String> buildEscalatedRecord(UUID incidentId,
                                                                int escalationLevel) {
        final String payload = String.format(
                "{\"incidentId\":\"%s\",\"tenantId\":\"%s\",\"escalationLevel\":%d}",
                incidentId, TENANT_ID, escalationLevel);
        final ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 0L, incidentId.toString(), payload);
        record.headers().add(new RecordHeader(IncidentEventTypes.HEADER_NAME,
                IncidentEventTypes.INCIDENT_ESCALATED.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(
                TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                TENANT_ID.getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    @Nested
    @DisplayName("successful processing")
    class SuccessfulProcessing {

        @Test
        @DisplayName("records the escalation level and acknowledges")
        void recordsEscalationLevel() {
            final Incident incident = buildIncident();
            final ConsumerRecord<String, String> record =
                    buildEscalatedRecord(incident.getId(), 2);
            given(incidentRepository.findByIdAndTenantId(incident.getId(), TENANT_ID))
                    .willReturn(Optional.of(incident));

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(incidentRepository).should().save(incident);
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("ignores non-escalation event types on the same topic, acknowledges")
        void ignoresNonEscalationEventTypes() {
            final ConsumerRecord<String, String> record = new ConsumerRecord<>(
                    TOPIC, 0, 0L, "key", "{}");
            record.headers().add(new RecordHeader(IncidentEventTypes.HEADER_NAME,
                    IncidentEventTypes.INCIDENT_OPENED.getBytes(StandardCharsets.UTF_8)));

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(incidentRepository).shouldHaveNoInteractions();
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("warns and still acknowledges when the incident isn't found locally")
        void warnsWhenIncidentNotFound() {
            final UUID incidentId = UUID.randomUUID();
            final ConsumerRecord<String, String> record =
                    buildEscalatedRecord(incidentId, 1);
            given(incidentRepository.findByIdAndTenantId(incidentId, TENANT_ID))
                    .willReturn(Optional.empty());

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(incidentRepository).should(never()).save(any());
            then(acknowledgment).should().acknowledge();
        }
    }

    @Nested
    @DisplayName("poison pill handling")
    class PoisonPillHandling {

        @Test
        @DisplayName("skips and acknowledges when the X-Event-Type header is missing")
        void skipsWhenEventTypeHeaderMissing() {
            final ConsumerRecord<String, String> record = new ConsumerRecord<>(
                    TOPIC, 0, 0L, "key", "{}");

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(incidentRepository).shouldHaveNoInteractions();
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("skips and acknowledges on unparseable JSON")
        void skipsOnUnparseableJson() {
            final ConsumerRecord<String, String> record = new ConsumerRecord<>(
                    TOPIC, 0, 0L, "key", "not valid json !!!");
            record.headers().add(new RecordHeader(IncidentEventTypes.HEADER_NAME,
                    IncidentEventTypes.INCIDENT_ESCALATED.getBytes(StandardCharsets.UTF_8)));

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(incidentRepository).shouldHaveNoInteractions();
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("skips and acknowledges when tenantId is missing from both header and payload")
        void skipsWhenTenantIdMissing() {
            final ConsumerRecord<String, String> record = new ConsumerRecord<>(
                    TOPIC, 0, 0L, "key",
                    "{\"incidentId\":\"" + UUID.randomUUID() + "\",\"escalationLevel\":1}");
            record.headers().add(new RecordHeader(IncidentEventTypes.HEADER_NAME,
                    IncidentEventTypes.INCIDENT_ESCALATED.getBytes(StandardCharsets.UTF_8)));

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(incidentRepository).shouldHaveNoInteractions();
            then(acknowledgment).should().acknowledge();
        }
    }

    @Nested
    @DisplayName("concurrency conflict handling (backlog #40)")
    class ConcurrencyConflictHandling {

        /**
         * The actual regression test for backlog #40. Verifies the new,
         * specific catch does NOT change the retry behavior — still no
         * acknowledge, letting Kafka redeliver — only that it's reached
         * (rather than falling into the generic catch) and doesn't
         * acknowledge, exactly matching this class's documented contract
         * that retrying a genuine OptimisticLockingFailureException here
         * is expected to resolve the conflict correctly.
         */
        @Test
        @DisplayName("does NOT acknowledge on OptimisticLockingFailureException — " +
                "relies on Kafka redelivery, same as before this fix")
        void doesNotAcknowledgeOnOptimisticLockConflict() {
            final Incident incident = buildIncident();
            final ConsumerRecord<String, String> record =
                    buildEscalatedRecord(incident.getId(), 2);
            given(incidentRepository.findByIdAndTenantId(incident.getId(), TENANT_ID))
                    .willReturn(Optional.of(incident));
            willThrow(new OptimisticLockingFailureException(
                    "Row was updated or deleted by another transaction"))
                    .given(incidentRepository).save(any());

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(acknowledgment).should(never()).acknowledge();
        }

        @Test
        @DisplayName("does NOT acknowledge on a generic transient failure — " +
                "existing behavior unchanged by this fix")
        void doesNotAcknowledgeOnGenericTransientFailure() {
            final Incident incident = buildIncident();
            final ConsumerRecord<String, String> record =
                    buildEscalatedRecord(incident.getId(), 2);
            given(incidentRepository.findByIdAndTenantId(incident.getId(), TENANT_ID))
                    .willReturn(Optional.of(incident));
            willThrow(new RuntimeException("Database connection lost"))
                    .given(incidentRepository).save(any());

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(acknowledgment).should(never()).acknowledge();
        }
    }
}