package com.incidentplatform.notification.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.incidentplatform.notification.service.NotificationService;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.events.IncidentEventTypes;
import com.incidentplatform.shared.kafka.DeadLetterPublisher;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("notification-service IncidentEventConsumer")
class IncidentEventConsumerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private Acknowledgment acknowledgment;

    @Mock
    private DeadLetterPublisher deadLetterPublisher;

    private IncidentEventConsumer consumer;
    private ObjectMapper objectMapper;

    private static final String TENANT_ID = "acme-corp";
    private static final String TOPIC = "incidents.lifecycle";
    private static final UUID INCIDENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        // Fixed (backlog #75): extractTenantId/parseJson moved to the
        // shared TenantKafkaRecordResolver — note the constructor's
        // argument order also changed to match production.
        consumer = new IncidentEventConsumer(
                notificationService, deadLetterPublisher,
                new TenantKafkaRecordResolver(objectMapper));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ConsumerRecord<String, String> buildRecord(String payload,
                                                       String tenantId,
                                                       String eventType) {
        final ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 0L, "key", payload);
        if (tenantId != null) {
            record.headers().add(new RecordHeader(
                    TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                    tenantId.getBytes(StandardCharsets.UTF_8)));
        }
        if (eventType != null) {
            record.headers().add(new RecordHeader(
                    IncidentEventTypes.HEADER_NAME,
                    eventType.getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }

    private String openedEvent() {
        return String.format("""
                {
                  "incidentId": "%s",
                  "tenantId": "%s",
                  "title": "High CPU",
                  "severity": "CRITICAL"
                }""", INCIDENT_ID, TENANT_ID);
    }

    private String acknowledgedEvent() {
        return String.format("""
                {
                  "incidentId": "%s",
                  "tenantId": "%s",
                  "title": "High CPU",
                  "severity": "CRITICAL",
                  "acknowledgedBy": "%s"
                }""", INCIDENT_ID, TENANT_ID, UUID.randomUUID());
    }

    private String resolvedEvent() {
        return String.format("""
                {
                  "incidentId": "%s",
                  "tenantId": "%s",
                  "title": "High CPU",
                  "severity": "HIGH",
                  "resolvedBy": "%s",
                  "durationMinutes": 30
                }""", INCIDENT_ID, TENANT_ID, UUID.randomUUID());
    }

    private String escalatedEvent() {
        return String.format("""
                {
                  "incidentId": "%s",
                  "tenantId": "%s",
                  "title": "High CPU",
                  "severity": "CRITICAL",
                  "escalationLevel": 1
                }""", INCIDENT_ID, TENANT_ID);
    }

    private String escalatedEvent(int level, String escalateToJson) {
        return String.format("""
                {
                  "incidentId": "%s",
                  "tenantId": "%s",
                  "escalateTo": %s,
                  "escalationLevel": %d,
                  "title": "High CPU",
                  "severity": "CRITICAL"
                }""", INCIDENT_ID, TENANT_ID, escalateToJson, level);
    }

    /**
     * Backlog #0-12: unlike {@link #openedEvent()}, this variant carries a
     * (possibly malformed) {@code teamId}. Used for the {@code TeamContext}
     * tests below — {@code extractTeamId} is exercised for every event type,
     * not just escalations, so a non-escalation event type is enough.
     */
    private String openedEvent(String teamIdJson) {
        return String.format("""
                {
                  "incidentId": "%s",
                  "tenantId": "%s",
                  "title": "High CPU",
                  "severity": "CRITICAL",
                  "teamId": %s
                }""", INCIDENT_ID, TENANT_ID, teamIdJson);
    }

    @Nested
    @DisplayName("tenant context management")
    class TenantContextManagement {

        @Test
        @DisplayName("should read tenantId from Kafka header not from event payload")
        void shouldReadTenantIdFromHeader() {
            // given
            final String payloadWithDifferentTenant = String.format("""
                    {
                      "incidentId": "%s",
                      "tenantId": "payload-tenant",
                      "title": "High CPU",
                      "severity": "CRITICAL"
                    }""", INCIDENT_ID);

            final ConsumerRecord<String, String> record =
                    buildRecord(payloadWithDifferentTenant, "header-tenant", IncidentEventTypes.INCIDENT_OPENED);

            final ArgumentCaptor<String> tenantCaptor =
                    ArgumentCaptor.forClass(String.class);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(notificationService).should().enqueue(
                    any(), any(), tenantCaptor.capture(), any(), any(), anyInt(), any(), any());
            assertThat(tenantCaptor.getValue()).isEqualTo("header-tenant");
        }

        @Test
        @DisplayName("should clear TenantContext after processing")
        void shouldClearTenantContextAfterProcessing() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(), TENANT_ID, IncidentEventTypes.INCIDENT_OPENED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("should clear TenantContext even when processing throws")
        void shouldClearTenantContextOnException() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(), TENANT_ID, IncidentEventTypes.INCIDENT_OPENED);

            org.mockito.BDDMockito.willThrow(new RuntimeException("db error"))
                    .given(notificationService)
                    .enqueue(any(), any(), any(), any(), any(), anyInt(), any(), any());

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            assertThat(TenantContext.getOrNull()).isNull();
        }

        @Test
        @DisplayName("should not leak tenantId between sequential records")
        void shouldNotLeakTenantIdBetweenRecords() {
            // given
            final ConsumerRecord<String, String> recordA =
                    buildRecord(openedEvent(), "tenant-a", IncidentEventTypes.INCIDENT_OPENED);
            final ConsumerRecord<String, String> recordB =
                    buildRecord(openedEvent(), "tenant-b", IncidentEventTypes.INCIDENT_OPENED);

            final ArgumentCaptor<String> tenantCaptor =
                    ArgumentCaptor.forClass(String.class);

            // when
            consumer.consumeIncidentEvent(recordA, acknowledgment);
            consumer.consumeIncidentEvent(recordB, acknowledgment);

            // then
            then(notificationService).should(org.mockito.Mockito.times(2))
                    .enqueue(any(), any(), tenantCaptor.capture(), any(), any(), anyInt(), any(), any());

            assertThat(tenantCaptor.getAllValues())
                    .containsExactly("tenant-a", "tenant-b");
        }
    }

    @Nested
    @DisplayName("event type routing")
    class EventTypeRouting {

        @Test
        @DisplayName("should route IncidentOpenedEvent to notificationService")
        void shouldRouteOpenedEvent() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(), TENANT_ID, IncidentEventTypes.INCIDENT_OPENED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(notificationService).should().enqueue(
                    eq("IncidentOpenedEvent"),
                    eq(INCIDENT_ID),
                    eq(TENANT_ID),
                    eq(Severity.CRITICAL),
                    eq("High CPU"),
                    eq(0),
                    isNull()
            , isNull());
        }

        @Test
        @DisplayName("should route IncidentAcknowledgedEvent to notificationService")
        void shouldRouteAcknowledgedEvent() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(acknowledgedEvent(), TENANT_ID, IncidentEventTypes.INCIDENT_ACKNOWLEDGED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(notificationService).should().enqueue(
                    eq(IncidentEventTypes.INCIDENT_ACKNOWLEDGED), any(), any(), any(), any(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("should route IncidentResolvedEvent to notificationService")
        void shouldRouteResolvedEvent() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(resolvedEvent(), TENANT_ID, IncidentEventTypes.INCIDENT_RESOLVED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(notificationService).should().enqueue(
                    eq(IncidentEventTypes.INCIDENT_RESOLVED), any(), any(), any(), any(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("should acknowledge and skip when X-Event-Type header is missing")
        void shouldAcknowledgeAndSkipWhenEventTypeHeaderMissing() {
            // given — no eventType header
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(), TENANT_ID, null);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then — acknowledged to skip, no routing
            then(acknowledgment).should().acknowledge();
            then(notificationService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("should route IncidentEscalatedEvent to notificationService")
        void shouldRouteEscalatedEvent() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(escalatedEvent(), TENANT_ID, IncidentEventTypes.INCIDENT_ESCALATED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(notificationService).should().enqueue(
                    eq(IncidentEventTypes.INCIDENT_ESCALATED), any(), any(), any(), any(), anyInt(), any(), any());
        }
    }

    @Nested
    @DisplayName("escalation context")
    class EscalationContext {

        @Test
        @DisplayName("should pass the escalation level and target of an IncidentEscalatedEvent")
        void shouldPassEscalationLevelAndTarget() {
            final UUID escalateTo = UUID.randomUUID();
            final ConsumerRecord<String, String> record = buildRecord(
                    escalatedEvent(2, "\"" + escalateTo + "\""), TENANT_ID,
                    IncidentEventTypes.INCIDENT_ESCALATED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(notificationService).should().enqueue(
                    eq(IncidentEventTypes.INCIDENT_ESCALATED), eq(INCIDENT_ID),
                    eq(TENANT_ID), eq(Severity.CRITICAL), eq("High CPU"),
                    eq(2), eq(escalateTo), isNull());
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("should pass a null target when escalateTo is JSON null")
        void shouldPassNullTargetWhenEscalateToIsNull() {
            final ConsumerRecord<String, String> record = buildRecord(
                    escalatedEvent(1, "null"), TENANT_ID,
                    IncidentEventTypes.INCIDENT_ESCALATED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(notificationService).should().enqueue(
                    eq(IncidentEventTypes.INCIDENT_ESCALATED), any(), any(),
                    any(), any(), eq(1), isNull(), isNull());
        }

        @Test
        @DisplayName("should pass a null target when escalateTo is absent")
        void shouldPassNullTargetWhenEscalateToIsAbsent() {
            final ConsumerRecord<String, String> record = buildRecord(
                    escalatedEvent(), TENANT_ID,
                    IncidentEventTypes.INCIDENT_ESCALATED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(notificationService).should().enqueue(
                    eq(IncidentEventTypes.INCIDENT_ESCALATED), any(), any(),
                    any(), any(), eq(1), isNull(), isNull());
        }

        /**
         * escalateTo is an optional hint. A malformed value must not turn
         * the whole escalation into a poison pill (which would drop an
         * alert that can still be delivered) — it is ignored and the
         * notification is still enqueued and acknowledged.
         */
        @Test
        @DisplayName("should still enqueue and acknowledge when escalateTo is not a UUID")
        void shouldIgnoreMalformedEscalateTo() {
            final ConsumerRecord<String, String> record = buildRecord(
                    escalatedEvent(2, "\"not-a-uuid\""), TENANT_ID,
                    IncidentEventTypes.INCIDENT_ESCALATED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(notificationService).should().enqueue(
                    eq(IncidentEventTypes.INCIDENT_ESCALATED), any(), any(),
                    any(), any(), eq(2), isNull(), isNull());
            then(deadLetterPublisher).shouldHaveNoInteractions();
            then(acknowledgment).should().acknowledge();
        }

        /**
         * escalationLevel is part of the idempotency key, so a missing or
         * out-of-range value must be rejected, not coerced: {@code asInt(0)}
         * would key every malformed escalation of an incident as level 0 and
         * silently discard all but the first, while an unbounded value lets a
         * producer mint a fresh key (and fresh notifications) per replay.
         */
        @org.junit.jupiter.params.ParameterizedTest(name = "escalationLevel={0} is a poison pill")
        @org.junit.jupiter.params.provider.ValueSource(strings = {
                "0", "-1", "3", "1000000", "\"abc\"", "1.5", "null"})
        @DisplayName("should dead-letter an escalation whose level is invalid")
        void shouldDeadLetterInvalidEscalationLevel(String levelJson) {
            final String payload = String.format("""
                    {
                      "incidentId": "%s",
                      "tenantId": "%s",
                      "escalationLevel": %s,
                      "title": "High CPU",
                      "severity": "CRITICAL"
                    }""", INCIDENT_ID, TENANT_ID, levelJson);
            final ConsumerRecord<String, String> record = buildRecord(
                    payload, TENANT_ID, IncidentEventTypes.INCIDENT_ESCALATED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(deadLetterPublisher).should().publish(
                    eq(payload), eq(TOPIC), eq(TENANT_ID), anyString());
            then(acknowledgment).should().acknowledge();
            then(notificationService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("should dead-letter an escalation with no escalationLevel at all")
        void shouldDeadLetterMissingEscalationLevel() {
            final String payload = String.format("""
                    {
                      "incidentId": "%s",
                      "tenantId": "%s",
                      "title": "High CPU",
                      "severity": "CRITICAL"
                    }""", INCIDENT_ID, TENANT_ID);
            final ConsumerRecord<String, String> record = buildRecord(
                    payload, TENANT_ID, IncidentEventTypes.INCIDENT_ESCALATED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(deadLetterPublisher).should().publish(
                    eq(payload), eq(TOPIC), eq(TENANT_ID), anyString());
            then(acknowledgment).should().acknowledge();
            then(notificationService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("should use level 0 and no target for events that are not escalations")
        void shouldUseLevelZeroForNonEscalationEvents() {
            // Even if a non-escalation payload happened to contain these
            // fields, they must not change how its notification is keyed.
            final String openedWithStrayFields = String.format("""
                    {
                      "incidentId": "%s",
                      "tenantId": "%s",
                      "title": "High CPU",
                      "severity": "CRITICAL",
                      "escalationLevel": 3,
                      "escalateTo": "%s"
                    }""", INCIDENT_ID, TENANT_ID, UUID.randomUUID());
            final ConsumerRecord<String, String> record = buildRecord(
                    openedWithStrayFields, TENANT_ID,
                    IncidentEventTypes.INCIDENT_OPENED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(notificationService).should().enqueue(
                    eq(IncidentEventTypes.INCIDENT_OPENED), any(), any(),
                    any(), any(), eq(0), isNull(), isNull());
        }
    }

    /**
     * Backlog #0-12: {@code extractTeamId} is applied to every event type
     * (unlike {@code escalationLevel}/{@code escalateTo}, which only apply to
     * {@code INCIDENT_ESCALATED}), so a non-escalation event type
     * ({@code INCIDENT_OPENED}) is enough to exercise it here.
     */
    @Nested
    @DisplayName("team context (backlog #0-12)")
    class TeamContext {

        @Test
        @DisplayName("should pass the teamId of any event type")
        void shouldPassTeamId() {
            final UUID teamId = UUID.randomUUID();
            final ConsumerRecord<String, String> record = buildRecord(
                    openedEvent("\"" + teamId + "\""), TENANT_ID,
                    IncidentEventTypes.INCIDENT_OPENED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(notificationService).should().enqueue(
                    eq(IncidentEventTypes.INCIDENT_OPENED), eq(INCIDENT_ID),
                    eq(TENANT_ID), eq(Severity.CRITICAL), eq("High CPU"),
                    eq(0), isNull(), eq(teamId));
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("should pass a null teamId when the field is JSON null")
        void shouldPassNullTeamIdWhenNull() {
            final ConsumerRecord<String, String> record = buildRecord(
                    openedEvent("null"), TENANT_ID,
                    IncidentEventTypes.INCIDENT_OPENED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(notificationService).should().enqueue(
                    eq(IncidentEventTypes.INCIDENT_OPENED), any(), any(),
                    any(), any(), eq(0), isNull(), isNull());
        }

        @Test
        @DisplayName("should pass a null teamId when the field is absent")
        void shouldPassNullTeamIdWhenAbsent() {
            final ConsumerRecord<String, String> record = buildRecord(
                    openedEvent(), TENANT_ID, IncidentEventTypes.INCIDENT_OPENED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(notificationService).should().enqueue(
                    eq(IncidentEventTypes.INCIDENT_OPENED), any(), any(),
                    any(), any(), eq(0), isNull(), isNull());
        }

        /**
         * teamId is an optional hint, like escalateTo — a malformed value
         * must not turn the event into a poison pill (which would drop an
         * alert that can still be delivered).
         */
        @Test
        @DisplayName("should still enqueue and acknowledge when teamId is not a UUID")
        void shouldIgnoreMalformedTeamId() {
            final ConsumerRecord<String, String> record = buildRecord(
                    openedEvent("\"not-a-uuid\""), TENANT_ID,
                    IncidentEventTypes.INCIDENT_OPENED);

            consumer.consumeIncidentEvent(record, acknowledgment);

            then(notificationService).should().enqueue(
                    eq(IncidentEventTypes.INCIDENT_OPENED), any(), any(),
                    any(), any(), eq(0), isNull(), isNull());
            then(deadLetterPublisher).shouldHaveNoInteractions();
            then(acknowledgment).should().acknowledge();
        }
    }

    @Nested
    @DisplayName("acknowledgment")
    class AcknowledgmentBehavior {

        @Test
        @DisplayName("should acknowledge after successful processing")
        void shouldAcknowledgeAfterSuccess() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(), TENANT_ID, IncidentEventTypes.INCIDENT_OPENED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("should NOT acknowledge when outbox enqueue fails — DB unavailable")
        void shouldNotAcknowledgeOnTransientException() {
            // given — RuntimeException is transient (Slack down, DB unavailable)
            // consumer should return without acknowledging so Kafka redelivers
            final ConsumerRecord<String, String> record =
                    buildRecord(openedEvent(), TENANT_ID,
                            IncidentEventTypes.INCIDENT_OPENED);

            org.mockito.BDDMockito.willThrow(new RuntimeException("Slack API down"))
                    .given(notificationService)
                    .enqueue(any(), any(), any(), any(), any(), anyInt(), any(), any());

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then — NOT acknowledged (DB write failed), Kafka will redeliver
            then(acknowledgment).should(org.mockito.Mockito.never()).acknowledge();
        }

        @Test
        @DisplayName("should route to DLT and acknowledge when severity is unrecognized — poison pill")
        void shouldAcknowledgeOnUnrecognizedSeverity() {
            // given — bad severity cannot be fixed by retrying
            final String badSeverityPayload = String.format("""
                    {
                      "incidentId": "%s",
                      "tenantId": "%s",
                      "title": "High CPU",
                      "severity": "UNKNOWN_SEVERITY"
                    }""", INCIDENT_ID, TENANT_ID);

            final ConsumerRecord<String, String> record =
                    buildRecord(badSeverityPayload, TENANT_ID,
                            IncidentEventTypes.INCIDENT_OPENED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then — routed to DLT (previously: only logged and discarded),
            // acknowledged to skip the poison pill
            then(deadLetterPublisher).should().publish(
                    eq(badSeverityPayload), eq(TOPIC), eq(TENANT_ID), anyString());
            then(acknowledgment).should().acknowledge();
        }

        @Test
        @DisplayName("should route to DLT and acknowledge when tenantId is missing from both header and payload — poison pill")
        void shouldRouteToDltWhenTenantIdMissing() {
            // given — no X-Tenant-Id header (buildRecord's tenantId param is
            // null) and no tenantId field in the payload either
            final String payloadWithoutTenantId = String.format("""
                    {
                      "incidentId": "%s",
                      "title": "High CPU",
                      "severity": "CRITICAL"
                    }""", INCIDENT_ID);

            final ConsumerRecord<String, String> record =
                    buildRecord(payloadWithoutTenantId, null,
                            IncidentEventTypes.INCIDENT_OPENED);

            // when
            consumer.consumeIncidentEvent(record, acknowledgment);

            // then — routed to DLT rather than silently discarded, tenantId
            // reported as "unknown" since it was never resolved
            then(deadLetterPublisher).should().publish(
                    eq(payloadWithoutTenantId), eq(TOPIC), eq("unknown"), anyString());
            then(acknowledgment).should().acknowledge();
            then(notificationService).shouldHaveNoInteractions();
        }
    }
}