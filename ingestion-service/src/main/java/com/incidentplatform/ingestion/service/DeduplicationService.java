package com.incidentplatform.ingestion.service;

import com.incidentplatform.shared.dto.UnifiedAlertDto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class DeduplicationService {

    private static final Logger log =
            LoggerFactory.getLogger(DeduplicationService.class);

    private static final String KEY_PREFIX = "dedup:";

    private final StringRedisTemplate redisTemplate;
    private final Duration dedupTtl;

    private final Counter redisErrorCounter;

    private final Counter duplicatesRejectedCounter;

    public DeduplicationService(
            StringRedisTemplate redisTemplate,
            MeterRegistry meterRegistry,
            @Value("${deduplication.ttl-minutes:5}") int ttlMinutes) {

        this.redisTemplate = redisTemplate;
        this.dedupTtl = Duration.ofMinutes(ttlMinutes);

        this.redisErrorCounter = Counter.builder("dedup.redis.errors")
                .description("Number of Redis errors during deduplication checks")
                .tag("service", "ingestion-service")
                .register(meterRegistry);

        this.duplicatesRejectedCounter = Counter.builder("dedup.duplicates.rejected")
                .description("Number of duplicate alerts rejected by deduplication")
                .tag("service", "ingestion-service")
                .register(meterRegistry);

        log.info("DeduplicationService initialized with TTL: {} minutes", ttlMinutes);
    }

    public boolean isDuplicate(UnifiedAlertDto alert) {
        final String key = KEY_PREFIX + alert.tenantId() + ":" + alert.fingerprint();

        try {
            final Boolean wasSet = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", dedupTtl);

            if (Boolean.TRUE.equals(wasSet)) {
                log.debug("New alert registered: fingerprint={}, tenant={}",
                        alert.fingerprint(), alert.tenantId());
                return false;
            } else {
                duplicatesRejectedCounter.increment();
                log.info("Duplicate alert rejected: fingerprint={}, source={}, " +
                                "tenant={}", alert.fingerprint(), alert.source(),
                        alert.tenantId());
                return true;
            }

        } catch (Exception e) {
            redisErrorCounter.increment();

            log.error("Redis deduplication failed for fingerprint: {}, " +
                            "source: {}, tenant: {}. " +
                            "Allowing alert through to avoid data loss. " +
                            "Check Redis connectivity. " +
                            "Redis error counter incremented for Prometheus alerting.",
                    alert.fingerprint(), alert.source(), alert.tenantId(), e);
            return false;
        }
    }
}