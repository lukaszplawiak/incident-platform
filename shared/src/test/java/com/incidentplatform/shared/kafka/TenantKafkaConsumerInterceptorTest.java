package com.incidentplatform.shared.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.incidentplatform.shared.security.TenantContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The poll-thread interceptor (backlog #0-58, written with #0-92): it validates
 * a batch and changes nothing. It must never set a tenant from a batch
 * (CLAUDE.md: a batch mixes tenants), and never log a header's value.
 */
@DisplayName("TenantKafkaConsumerInterceptor")
class TenantKafkaConsumerInterceptorTest {

    private final TenantKafkaConsumerInterceptor<String, String> interceptor =
            new TenantKafkaConsumerInterceptor<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        ((Logger) LoggerFactory.getLogger(TenantKafkaConsumerInterceptor.class)).addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger(TenantKafkaConsumerInterceptor.class)).detachAppender(logs);
        TenantContext.clear();
        MDC.clear();
    }

    private static ConsumerRecord<String, String> record(long offset, String tenantHeader) {
        final ConsumerRecord<String, String> record = new ConsumerRecord<>("alerts.raw", 0, offset, "k", "{}");
        if (tenantHeader != null) {
            record.headers().add(new RecordHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                    tenantHeader.getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }

    private static ConsumerRecords<String, String> batch(List<ConsumerRecord<String, String>> records) {
        return new ConsumerRecords<>(Map.of(new TopicPartition("alerts.raw", 0), records));
    }

    @Test
    @DisplayName("returns the batch unchanged and sets no tenant from it")
    void batchUnchangedNoTenant() {
        final ConsumerRecords<String, String> records =
                batch(List.of(record(1, "acme"), record(2, "globex")));

        assertThat(interceptor.onConsume(records)).isSameAs(records);
        assertThat(TenantContext.isSet()).isFalse();
        assertThat(MDC.get(TenantContext.MDC_TENANT_KEY)).isNull();
    }

    @Test
    @DisplayName("a record without a tenant header is logged by position only, never by value")
    void missingHeaderLogged() {
        interceptor.onConsume(batch(List.of(record(7, null), record(8, "evil\nFAKE LOG LINE"))));

        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("offset=7"));
        assertThat(logs.list).noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains("FAKE"));
    }

    @Test
    @DisplayName("an empty batch passes untouched")
    void emptyBatch() {
        final ConsumerRecords<String, String> empty = ConsumerRecords.empty();

        assertThat(interceptor.onConsume(empty)).isSameAs(empty);
    }
}
