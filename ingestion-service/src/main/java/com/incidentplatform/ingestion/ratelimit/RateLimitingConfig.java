package com.incidentplatform.ingestion.ratelimit;

import com.incidentplatform.shared.domain.Severity;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Factory for bucket4j {@link Bucket} instances used by the rate limiter.
 *
 * <p>Configuration is injected via {@link RateLimitingProperties} —
 * a single {@code @ConfigurationProperties} record that owns all
 * {@code rate-limiting.*} properties. No {@code @Value} annotations
 * are needed here.
 */
@Configuration
@EnableConfigurationProperties(RateLimitingProperties.class)
public class RateLimitingConfig {

    private final RateLimitingProperties props;

    public RateLimitingConfig(RateLimitingProperties props) {
        this.props = props;
    }

    public Bucket createTenantBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(props.tenant().capacity())
                        .refillGreedy(
                                props.tenant().refillTokens(),
                                Duration.ofSeconds(props.tenant().refillPeriodSeconds()))
                        .build())
                .build();
    }

    public Bucket createIpBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(props.ip().capacity())
                        .refillGreedy(
                                props.ip().refillTokens(),
                                Duration.ofSeconds(props.ip().refillPeriodSeconds()))
                        .build())
                .build();
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