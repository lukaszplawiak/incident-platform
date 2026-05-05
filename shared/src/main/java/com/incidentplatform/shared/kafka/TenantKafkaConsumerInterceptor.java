package com.incidentplatform.shared.kafka;

import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        // Validate that every record in the batch has a tenant header.
        // TenantContext is NOT set here — see class Javadoc for explanation.
        for (var record : records) {
            final Header tenantHeader = record.headers()
                    .lastHeader(TenantKafkaProducerInterceptor.TENANT_ID_HEADER);

            if (tenantHeader == null) {
                log.warn("Missing X-Tenant-Id header in Kafka record — " +
                                "record will be processed without tenant header validation. " +
                                "topic={}, partition={}, offset={}",
                        record.topic(), record.partition(), record.offset());
            } else {
                log.debug("Validated tenant header present: topic={}, " +
                                "partition={}, offset={}",
                        record.topic(), record.partition(), record.offset());
            }
        }

        return records;
    }

    @Override
    public void onCommit(Map<TopicPartition, OffsetAndMetadata> offsets) {
        log.debug("Kafka batch committed, partitions: {}", offsets.keySet());
    }

    @Override
    public void close() {
        log.info("TenantKafkaConsumerInterceptor closed");
    }

    @Override
    public void configure(Map<String, ?> configs) {
        log.info("TenantKafkaConsumerInterceptor configured");
    }
}