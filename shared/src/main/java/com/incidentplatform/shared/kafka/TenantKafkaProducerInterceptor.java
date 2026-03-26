package com.incidentplatform.shared.kafka;

import com.incidentplatform.shared.security.TenantContext;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class TenantKafkaProducerInterceptor<K, V>
        implements ProducerInterceptor<K, V> {

    private static final Logger log =
            LoggerFactory.getLogger(TenantKafkaProducerInterceptor.class);

    public static final String TENANT_ID_HEADER = "X-Tenant-Id";

    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        String tenantId = TenantContext.getOrNull();

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