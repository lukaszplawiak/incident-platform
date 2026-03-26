package com.incidentplatform.shared.kafka;

import com.incidentplatform.shared.security.TenantContext;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class TenantKafkaConsumerInterceptor<K, V>
        implements ConsumerInterceptor<K, V> {

    private static final Logger log =
            LoggerFactory.getLogger(TenantKafkaConsumerInterceptor.class);

    @Override
    public ConsumerRecords<K, V> onConsume(ConsumerRecords<K, V> records) {
        if (records.isEmpty()) {
            return records;
        }

        for (var record : records) {
            Header tenantHeader = record.headers()
                    .lastHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER);

            if (tenantHeader != null) {
                String tenantId = new String(
                        tenantHeader.value(),
                        StandardCharsets.UTF_8
                );
                TenantContext.set(tenantId);
                log.debug("TenantContext set from Kafka header, topic: {}, " +
                                "partition: {}, offset: {}, tenantId: {}",
                        record.topic(), record.partition(),
                        record.offset(), tenantId);
                break;
            } else {
                log.warn("No tenant header found in Kafka record, topic: {}, " +
                                "partition: {}, offset: {}. " +
                                "Message will be processed without tenant context.",
                        record.topic(), record.partition(), record.offset());
            }
        }

        return records;
    }

    @Override
    public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {
        if (TenantContext.isSet()) {
            log.debug("Clearing TenantContext after Kafka batch commit, " +
                    "partitions: {}", offsets.keySet());
            TenantContext.clear();
        }
    }

    @Override
    public void close() {
        if (TenantContext.isSet()) {
            log.warn("TenantContext was set when consumer closed — clearing");
            TenantContext.clear();
        }
        log.info("TenantKafkaConsumerInterceptor closed");
    }

    @Override
    public void configure(Map<String, ?> configs) {
        log.info("TenantKafkaConsumerInterceptor configured");
    }
}