package com.incidentplatform.ingestion.service;

import com.incidentplatform.ingestion.config.DeduplicationProperties;
import com.incidentplatform.shared.dto.UnifiedAlertDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis-backed alert deduplication using {@code SETNX} semantics
 * ({@code setIfAbsent} with a TTL).
 *
 * <h2>Fixed: the @CircuitBreaker never actually opened</h2>
 * Previously {@code isDuplicate} wrapped its Redis call in its own
 * {@code try/catch (Exception e)} and returned {@code false} (fail open)
 * from inside the catch block. Resilience4j's {@code @CircuitBreaker} is
 * an AOP proxy wrapped around the whole method call — it only sees
 * whether the method call it intercepted returned normally or threw. A
 * caught-and-swallowed exception looks exactly like success from the
 * proxy's point of view: {@code circuitBreaker.recordFailure(...)} was
 * never invoked, the failure rate stayed at 0%, the circuit never
 * opened, and {@code isDuplicateFallback} was unreachable dead code.
 *
 * <p>The internal try/catch is removed here — a real Redis failure
 * (matching {@code resilience4j.circuitbreaker.instances.redis-dedup
 * .record-exceptions} in application.yml: connection failures, not
 * arbitrary exceptions) now propagates out of {@code isDuplicate} so the
 * proxy actually sees it, records it, and — once the failure-rate
 * threshold is crossed — opens the circuit and calls
 * {@link #isDuplicateFallback} directly, without waiting for another
 * Redis timeout first. Same fix applied to the same bug found
 * independently in {@code OncallClientImpl} and {@code IncidentAckClient}
 * (notification-service) — {@code escalation-service}'s
 * {@code OncallServiceClient} and {@code postmortem-service}'s
 * {@code GeminiClientImpl} already did this correctly and were used as
 * the reference pattern here.
 */
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
            DeduplicationProperties properties) {

        this.redisTemplate = redisTemplate;
        this.dedupTtl = properties.ttl();

        this.redisErrorCounter = Counter.builder("dedup.redis.errors")
                .description("Number of Redis errors during deduplication checks")
                .tag("service", "ingestion-service")
                .register(meterRegistry);

        this.duplicatesRejectedCounter = Counter.builder("dedup.duplicates.rejected")
                .description("Number of duplicate alerts rejected by deduplication")
                .tag("service", "ingestion-service")
                .register(meterRegistry);

        log.info("DeduplicationService initialized with TTL: {}", properties.ttl());
    }

    @CircuitBreaker(name = "redis-dedup", fallbackMethod = "isDuplicateFallback")
    public boolean isDuplicate(UnifiedAlertDto alert) {
        final String key = KEY_PREFIX + alert.tenantId() + ":" + alert.fingerprint();

        // No try/catch here — a Redis failure must propagate out of this
        // method for @CircuitBreaker's proxy to see it and record it.
        // See this class's Javadoc.
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
    }

    /**
     * Called by the Resilience4j proxy — either when {@link #isDuplicate}
     * throws a matching exception, or when the circuit is already OPEN
     * (in which case {@code ex} is a
     * {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException},
     * not a Redis exception — the broad {@code Exception} parameter type
     * here is Resilience4j's own required convention for fallback
     * methods, not the anti-pattern this class used to have internally).
     */
    boolean isDuplicateFallback(UnifiedAlertDto alert, Exception ex) {
        redisErrorCounter.increment();
        log.error("Redis unavailable for deduplication — allowing alert through " +
                        "to avoid data loss: fingerprint={}, tenant={}, error={}",
                alert.fingerprint(), alert.tenantId(), ex.getMessage(), ex);
        return false;
    }
}