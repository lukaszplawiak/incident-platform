package com.incidentplatform.auth.config;

import com.incidentplatform.auth.service.WorkSimulator;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Enables scheduled tasks and ShedLock for auth-service.
 *
 * <p>ShedLock prevents {@code InviteEmailScheduler} from running concurrently
 * across multiple auth-service instances — only one instance processes the
 * outbox at a time, preventing duplicate invite emails.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "5m")
public class SchedulerConfig {

    /**
     * Production {@link WorkSimulator} for {@code ForgotPasswordService}.
     *
     * <p>Sleeps 8–11ms to match the typical execution time of the "user exists"
     * path (DB find + token generate + two DB writes ≈ 10ms). The random jitter
     * (0–3ms) prevents statistical fingerprinting by making the sleep duration
     * indistinguishable from normal DB IO jitter.
     *
     * <p>Why {@code Thread.sleep()} and not BCrypt dummy hash:
     * The normal path uses SHA-256 for token generation (not BCrypt), so
     * BCrypt dummy hash (~100ms) would be 10x slower than the real flow —
     * creating an inverse timing signal. Sleep of ~10ms matches the real
     * flow duration.
     *
     * <p>In tests, inject {@code () -> {}} (no-op lambda) instead.
     */
    @Bean
    public WorkSimulator forgotPasswordWorkSimulator() {
        return () -> {
            try {
                // 8ms base + 0..3ms jitter
                Thread.sleep(8 + ThreadLocalRandom.current().nextLong(4));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build()
        );
    }
}