package com.incidentplatform.shared.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.incidentplatform.shared.security.TenantContext;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Which X-Tenant-Id an outgoing record ends up with (backlog #0-88: an explicit header wins). */
@DisplayName("TenantKafkaProducerInterceptor")
class TenantKafkaProducerInterceptorTest {

    private final TenantKafkaProducerInterceptor<String, String> interceptor = new TenantKafkaProducerInterceptor<>();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static List<String> tenantHeaders(ProducerRecord<String, String> record) {
        final List<String> values = new ArrayList<>();
        for (final Header header : record.headers().headers(TenantKafkaProducerInterceptor.TENANT_ID_HEADER)) {
            values.add(new String(header.value(), StandardCharsets.UTF_8));
        }
        return values;
    }

    @Test
    @DisplayName("stamps the header from TenantContext")
    void stampsFromContext() {
        TenantContext.set("acme");

        assertThat(tenantHeaders(interceptor.onSend(new ProducerRecord<>("t", "k", "v")))).containsExactly("acme");
    }

    @Test
    @DisplayName("without a context the record goes without a header")
    void noContextNoHeader() {
        assertThat(tenantHeaders(interceptor.onSend(new ProducerRecord<>("t", "k", "v")))).isEmpty();
    }

    @Test
    @DisplayName("keeps a header the sender set, even when the context names another tenant (review of #0-88)")
    void explicitHeaderWins() {
        TenantContext.set("context-tenant");
        final ProducerRecord<String, String> record = new ProducerRecord<>("audit.events", "acme", "v");
        record.headers().add(new RecordHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                "acme".getBytes(StandardCharsets.UTF_8)));

        final ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            assertThat(tenantHeaders(interceptor.onSend(record)))
                    .as("one header, the record's own; consumers read the last").containsExactly("acme");
            assertThat(logs.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("acme").contains("context-tenant");
            });
        } finally {
            release(logs);
        }
    }

    @Test
    @DisplayName("a header that matches the context is kept without a warning")
    void matchingHeaderNoWarning() {
        TenantContext.set("acme");
        final ProducerRecord<String, String> record = new ProducerRecord<>("audit.events", "acme", "v");
        record.headers().add(new RecordHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER,
                "acme".getBytes(StandardCharsets.UTF_8)));
        final ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            assertThat(tenantHeaders(interceptor.onSend(record))).containsExactly("acme");
            assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.WARN);
        } finally {
            release(logs);
        }
    }

    private static ListAppender<ILoggingEvent> captureLogs() {
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(TenantKafkaProducerInterceptor.class)).addAppender(appender);
        return appender;
    }

    private static void release(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(TenantKafkaProducerInterceptor.class)).detachAppender(appender);
    }
}
