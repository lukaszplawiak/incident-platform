package com.incidentplatform.shared.kafka;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.incidentplatform.shared.security.TenantContext;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The producer interceptor checks a record's tenant header and writes nothing
 * (backlog #0-91): the header comes from the sender ({@link TenantRecords}),
 * never from the thread's context.
 */
@DisplayName("TenantKafkaProducerInterceptor (backlog #0-91)")
class TenantKafkaProducerInterceptorTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private TenantKafkaProducerInterceptor<String, String> interceptor;

    @BeforeEach
    void setUp() {
        Metrics.globalRegistry.add(meters);
        interceptor = new TenantKafkaProducerInterceptor<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(TenantKafkaProducerInterceptor.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(TenantKafkaProducerInterceptor.class)).detachAppender(logs);
        Metrics.globalRegistry.remove(meters);
        TenantContext.clear();
    }

    private double count(String reason) {
        return meters.counter(TenantKafkaProducerInterceptor.COUNTER, "reason", reason).count();
    }

    private static ProducerRecord<String, String> withHeader(String topic, String tenant) {
        final ProducerRecord<String, String> record = new ProducerRecord<>(topic, "k", "v");
        record.headers().add(new RecordHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                tenant.getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    @Test
    @DisplayName("both counters exist at zero before any record, so the alert sees the first one")
    void countersRegisteredAtZero() {
        assertThat(meters.find(TenantKafkaProducerInterceptor.COUNTER).counters()).hasSize(2)
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    @DisplayName("a valid header passes untouched and uncounted")
    void validHeaderPasses() {
        final ProducerRecord<String, String> record = withHeader("audit.events", "acme");

        assertThat(interceptor.onSend(record)).isSameAs(record);
        assertThat(record.headers().headers(TenantKafkaProducerInterceptor.TENANT_ID_HEADER)).hasSize(1);
        assertThat(count("missing") + count("invalid")).isZero();
    }

    @Test
    @DisplayName("never adds a header from TenantContext any more: a missing one is counted")
    void writesNothingFromContext() {
        TenantContext.set("context-tenant");
        final ProducerRecord<String, String> record = new ProducerRecord<>("incidents.lifecycle", "k", "v");

        interceptor.onSend(record);

        assertThat(record.headers().lastHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER)).isNull();
        assertThat(count("missing")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an invalid header is counted and logged without its value")
    void invalidHeaderCounted() {
        interceptor.onSend(withHeader("alerts.raw", "evil\nFAKE LOG LINE"));

        assertThat(count("invalid")).isEqualTo(1.0);
        assertThat(logs.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).doesNotContain("FAKE LOG LINE"));
    }

    @Test
    @DisplayName("a dead-letter record marked as without a tenant is expected and not counted")
    void deadLetterWithoutTenant() {
        interceptor.onSend(TenantRecords.withoutTenant("incidents.dead-letter", "k", "v"));

        assertThat(count("missing")).isZero();
    }

    @Test
    @DisplayName("the topic name alone exempts nothing: an unmarked dead-letter record, or the marker off a "
            + "dead-letter topic, is counted (found in review)")
    void topicNameAloneExemptsNothing() {
        interceptor.onSend(new ProducerRecord<>("incidents.dead-letter", "k", "v"));
        final ProducerRecord<String, String> marked = new ProducerRecord<>("incidents.lifecycle", "k", "v");
        marked.headers().add(TenantRecords.TENANT_UNRESOLVED_HEADER, "true".getBytes(StandardCharsets.UTF_8));
        interceptor.onSend(marked);

        assertThat(count("missing")).isEqualTo(2.0);
    }
}
