package com.incidentplatform.shared.kafka;

import com.incidentplatform.shared.security.TenantContext;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Stamps {@code X-Tenant-Id} on an outgoing record from {@code TenantContext}.
 *
 * <h2>Fixed (backlog #0-88): an explicit header wins</h2>
 * Senders that know the record's tenant set the header themselves (audit,
 * alert and incident-event senders). This interceptor used to append a
 * second one from the thread's context, and every consumer reads the last
 * header, so a context different from the record's own tenant would have
 * relabelled it (found in review). A record that already carries the header
 * is now left alone; a mismatch with the context is logged.
 */
public class TenantKafkaProducerInterceptor<K, V>
        implements ProducerInterceptor<K, V> {

    private static final Logger log =
            LoggerFactory.getLogger(TenantKafkaProducerInterceptor.class);

    public static final String TENANT_ID_HEADER = "X-Tenant-Id";

    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        String tenantId = TenantContext.getOrNull();

        final Header explicit = record.headers().lastHeader(TENANT_ID_HEADER);
        if (explicit != null) {
            final String recordTenant = new String(explicit.value(), StandardCharsets.UTF_8);
            if (tenantId != null && !tenantId.equals(recordTenant)) {
                log.warn("Kafka record to topic {} carries tenant header {} but the thread's context is {}; "
                        + "keeping the record's own header", record.topic(), recordTenant, tenantId);
            }
            return record;
        }

        if (tenantId != null) {
            record.headers().add(
                    TENANT_ID_HEADER,
                    tenantId.getBytes(StandardCharsets.UTF_8)
            );
            log.debug("Added tenant header to Kafka record, topic: {}, tenantId: {}",
                    record.topic(), tenantId);
        } else {
            log.warn("No tenant context when producing Kafka record to topic: {}. " +
                            "Message will be sent without tenant header. " +
                            "If this is a scheduled job, this is expected behavior.",
                    record.topic());
        }

        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        if (exception != null) {
            log.error("Kafka producer failed to send message to topic: {}, partition: {}",
                    metadata != null ? metadata.topic() : "unknown",
                    metadata != null ? metadata.partition() : "unknown",
                    exception);
        } else {
            log.debug("Kafka message acknowledged, topic: {}, partition: {}, offset: {}",
                    metadata.topic(), metadata.partition(), metadata.offset());
        }
    }

    @Override
    public void close() {
        log.info("TenantKafkaProducerInterceptor closed");
    }

    @Override
    public void configure(Map<String, ?> configs) {
        log.info("TenantKafkaProducerInterceptor configured");
    }
}