package com.incidentplatform.shared.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
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
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantKafkaRecordInterceptor")
class TenantKafkaRecordInterceptorTest {

    @Mock
    private Consumer<String, String> consumer;

    private MeterRegistry meterRegistry;
    private TenantKafkaRecordInterceptor<String, String> interceptor;

    private static final String TOPIC     = "incidents.lifecycle";
    private static final String TENANT_ID = "acme-corp";

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        interceptor   = new TenantKafkaRecordInterceptor<>(meterRegistry);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private ConsumerRecord<String, String> buildRecord(String payload,
                                                       String tenantId) {
        final ConsumerRecord<String, String> record =
                new ConsumerRecord<>(TOPIC, 0, 42L, "key", payload);
        if (tenantId != null) {
            record.headers().add(new RecordHeader(
                    TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                    tenantId.getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }

    // ─── MDC enrichment ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("MDC enrichment")
    class MdcEnrichment {

        @Test
        @DisplayName("should set tenantId in MDC from X-Tenant-Id header")
        void shouldSetTenantIdInMdcFromHeader() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord("{}", TENANT_ID);

            // when
            interceptor.intercept(record, consumer);

            // then
            assertThat(MDC.get(TenantKafkaRecordInterceptor.MDC_TENANT_ID))
                    .isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("should set kafkaMessageId in MDC as topic-partition-offset")
        void shouldSetKafkaMessageIdInMdc() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord("{}", TENANT_ID);

            // when
            interceptor.intercept(record, consumer);

            // then
            assertThat(MDC.get(TenantKafkaRecordInterceptor.MDC_MESSAGE_ID))
                    .isEqualTo(TOPIC + "-0-42");
        }

        @Test
        @DisplayName("should set tenantId to '_missing' when X-Tenant-Id header is missing (backlog #0-92)")
        void shouldSetUnknownTenantIdWhenHeaderMissing() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord("{}", null);

            // when
            interceptor.intercept(record, consumer);

            // then
            assertThat(MDC.get(TenantKafkaRecordInterceptor.MDC_TENANT_ID))
                    .isEqualTo(TenantKafkaRecordInterceptor.MISSING);
        }

        @Test
        @DisplayName("an invalid header never reaches the MDC (backlog #0-92)")
        void invalidHeaderNotInMdc() {
            final ConsumerRecord<String, String> record =
                    buildRecord("{}", "evil\nFAKE LOG LINE");

            interceptor.intercept(record, consumer);

            assertThat(MDC.get(TenantKafkaRecordInterceptor.MDC_TENANT_ID))
                    .isEqualTo(TenantKafkaRecordInterceptor.INVALID);
        }

        @Test
        @DisplayName("should clear MDC after success()")
        void shouldClearMdcAfterSuccess() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord("{}", TENANT_ID);

            // when
            interceptor.intercept(record, consumer);
            interceptor.success(record, consumer);

            // then — MDC cleaned so it doesn't leak to the next record
            assertThat(MDC.get(TenantKafkaRecordInterceptor.MDC_TENANT_ID)).isNull();
            assertThat(MDC.get(TenantKafkaRecordInterceptor.MDC_MESSAGE_ID)).isNull();
        }

        @Test
        @DisplayName("should clear MDC after failure()")
        void shouldClearMdcAfterFailure() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord("{}", TENANT_ID);

            // when
            interceptor.intercept(record, consumer);
            interceptor.failure(record, new RuntimeException("DB error"), consumer);

            // then — MDC must be cleaned even on exception
            assertThat(MDC.get(TenantKafkaRecordInterceptor.MDC_TENANT_ID)).isNull();
            assertThat(MDC.get(TenantKafkaRecordInterceptor.MDC_MESSAGE_ID)).isNull();
        }

        @Test
        @DisplayName("should not leak MDC between sequential records")
        void shouldNotLeakMdcBetweenRecords() {
            // given
            final ConsumerRecord<String, String> recordA =
                    buildRecord("{}", "tenant-a");
            final ConsumerRecord<String, String> recordB =
                    buildRecord("{}", "tenant-b");

            // when — simulate two records processed sequentially on same thread
            interceptor.intercept(recordA, consumer);
            assertThat(MDC.get(TenantKafkaRecordInterceptor.MDC_TENANT_ID))
                    .isEqualTo("tenant-a");

            interceptor.success(recordA, consumer);

            interceptor.intercept(recordB, consumer);

            // then — tenant-b, not tenant-a
            assertThat(MDC.get(TenantKafkaRecordInterceptor.MDC_TENANT_ID))
                    .isEqualTo("tenant-b");

            interceptor.success(recordB, consumer);
        }
    }

    // ─── Micrometer metrics ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Micrometer metrics")
    class Metrics {

        @Test
        @DisplayName("should increment kafka.records.received per topic")
        void shouldIncrementReceivedCounter() {
            interceptor.intercept(buildRecord("{}", TENANT_ID), consumer);
            interceptor.intercept(buildRecord("{}", "tenant-b"), consumer);

            final Counter counter = meterRegistry.find("kafka.records.received")
                    .tag("topic", TOPIC)
                    .counter();

            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(2.0);
        }

        /**
         * Found in the end-to-end test of #0-92: a forged but well-formed
         * header used to open a series of its own, so a producer could add
         * series without bound. No value from a record is a tag.
         */
        @Test
        @DisplayName("carries no tenant tag: headers cannot add series (backlog #0-92)")
        void noTenantTag() {
            for (int i = 0; i < 20; i++) {
                interceptor.intercept(buildRecord("{}", "forged-" + i), consumer);
            }

            assertThat(meterRegistry.find("kafka.records.received").counters()).singleElement()
                    .satisfies(c -> assertThat(c.getId().getTag("tenant")).isNull());
        }

        @Test
        @DisplayName("should register kafka.record.processing.duration timer after success")
        void shouldRegisterProcessingDurationTimerAfterSuccess() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord("{}", TENANT_ID);

            // when
            interceptor.intercept(record, consumer);
            interceptor.success(record, consumer);

            // then
            assertThat(meterRegistry.find("kafka.record.processing.duration")
                    .tag("topic", TOPIC)
                    .timer()).isNotNull();
        }

        @Test
        @DisplayName("should record processing duration after failure")
        void shouldRecordProcessingDurationAfterFailure() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord("{}", TENANT_ID);

            // when
            interceptor.intercept(record, consumer);
            interceptor.failure(record, new RuntimeException("DB error"), consumer);

            // then — timer recorded even for failed records
            assertThat(meterRegistry.find("kafka.record.processing.duration")
                    .tag("topic", TOPIC)
                    .timer().count()).isEqualTo(1);
        }
    }

    // ─── record passthrough ───────────────────────────────────────────────────

    @Nested
    @DisplayName("record passthrough")
    class RecordPassthrough {

        @Test
        @DisplayName("should return the same record unchanged")
        void shouldReturnRecordUnchanged() {
            // given
            final ConsumerRecord<String, String> record =
                    buildRecord("{\"payload\":\"value\"}", TENANT_ID);

            // when
            final ConsumerRecord<String, String> result =
                    interceptor.intercept(record, consumer);

            // then — interceptor must not modify the record
            assertThat(result).isSameAs(record);
            assertThat(result.value()).isEqualTo("{\"payload\":\"value\"}");
            assertThat(result.topic()).isEqualTo(TOPIC);
            assertThat(result.partition()).isEqualTo(0);
            assertThat(result.offset()).isEqualTo(42L);
        }
    }
}