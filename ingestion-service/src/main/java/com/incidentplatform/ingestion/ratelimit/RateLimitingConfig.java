package com.incidentplatform.ingestion.ratelimit;

import com.incidentplatform.shared.domain.Severity;
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

    public long getSeverityCapacity(Severity severity) {
        if (severity == null) return props.severity().medium().capacity();
        return switch (severity) {
            case CRITICAL -> props.severity().critical().capacity();
            case HIGH     -> props.severity().high().capacity();
            case MEDIUM   -> props.severity().medium().capacity();
            case LOW      -> props.severity().low().capacity();
        };
    }

    // Expose properties for callers that need raw values
    // (e.g. metrics reporting, diagnostics endpoints)
    public RateLimitingProperties properties() {
        return props;
    }
}