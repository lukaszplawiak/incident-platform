package com.incidentplatform.ingestion.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Factory for bucket4j {@link BucketConfiguration} instances used by the
 * rate limiter.
 *
 * <p>Configuration is injected via {@link RateLimitingProperties} —
 * a single {@code @ConfigurationProperties} record that owns all
 * {@code rate-limiting.*} properties. No {@code @Value} annotations
 * are needed here.
 *
 * <h2>BucketConfiguration, not Bucket</h2>
 * Previously this returned a concrete, local {@code Bucket} — an
 * in-memory object holding its own mutable token state, cached forever
 * in a {@code ConcurrentHashMap} by {@link RateLimitingService} (the bug
 * this class was rewritten to help fix — see that class's Javadoc).
 * With the Redis-backed {@code ProxyManager} now used instead, the token
 * state lives in Redis, addressed by key; this class only needs to
 * describe the *rules* (capacity, refill rate) via
 * {@link BucketConfiguration}, which {@link RateLimitingService} passes
 * to {@code proxyManager.builder().build(key, configuration)} on every
 * call. {@link BucketConfiguration} is immutable and stateless, so a
 * single instance is safely reused across every tenant/IP — there was
 * never a reason to construct a new one per key in the first place.
 *
 * <h2>Fixed (backlog #73): removed the half-built per-severity capacity,
 * not completed</h2>
 * This class previously also had {@code getSeverityCapacity(Severity)},
 * reading a {@code rate-limiting.severity.*} config section with a fixed
 * capacity per severity level (critical=1000, high=500, medium=100,
 * low=50) — but nothing ever called it. Investigated completing it
 * (wiring a third, severity-keyed bucket into
 * {@code RateLimitingService.tryConsume}) rather than just deleting dead
 * code, and concluded it was the wrong mechanism entirely, for two
 * independent reasons:
 * <ol>
 *   <li>{@code tryConsume} is called once per HTTP request, before the
 *       payload is normalized — for Prometheus specifically, one webhook
 *       call legitimately batches many alerts of different severities
 *       (see {@code PrometheusNormalizer}/{@code NormalizationResult}'s
 *       own Javadoc, backlog #69). There is no single well-defined
 *       "severity of this request" to check at that point.</li>
 *   <li>Even where severity IS known up front (single-alert sources),
 *       a fixed, always-enforced token bucket per severity is not what
 *       production alerting systems mean by protecting critical alerts.
 *       That pattern is priority-based load shedding — conditional on
 *       actual system stress, shedding low-priority work first while
 *       preserving high-priority work — not a constant quota enforced
 *       regardless of load. A fixed cap provides no actual guarantee
 *       that a critical alert is never rejected; it only limits noise
 *       for whichever category it's applied to.</li>
 * </ol>
 * The right mechanism for genuine severity-aware protection already has
 * a home, explicitly planned and deliberately deferred, in
 * {@code application.yml}'s own architecture roadmap comment above the
 * {@code rate-limiting} section: "Layer 4: Separate Kafka topics per
 * severity" — differentiated downstream processing/prioritization, not
 * an additional reject-gate at ingestion. Completing the old
 * {@code severity} config as originally shaped would have built the
 * wrong thing, needing to be torn out again once Layer 4 is properly
 * implemented. See that TODO for the tracked follow-up.
 */
@Configuration
@EnableConfigurationProperties(RateLimitingProperties.class)
public class RateLimitingConfig {

    private final RateLimitingProperties props;

    private final BucketConfiguration tenantBucketConfiguration;
    private final BucketConfiguration ipBucketConfiguration;

    public RateLimitingConfig(RateLimitingProperties props) {
        this.props = props;

        this.tenantBucketConfiguration = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(props.tenant().capacity())
                        .refillGreedy(
                                props.tenant().refillTokens(),
                                Duration.ofSeconds(props.tenant().refillPeriodSeconds()))
                        .build())
                .build();

        this.ipBucketConfiguration = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(props.ip().capacity())
                        .refillGreedy(
                                props.ip().refillTokens(),
                                Duration.ofSeconds(props.ip().refillPeriodSeconds()))
                        .build())
                .build();
    }

    public BucketConfiguration tenantBucketConfiguration() {
        return tenantBucketConfiguration;
    }

    public BucketConfiguration ipBucketConfiguration() {
        return ipBucketConfiguration;
    }

    // Expose properties for callers that need raw values
    // (e.g. metrics reporting, diagnostics endpoints)
    public RateLimitingProperties properties() {
        return props;
    }
}