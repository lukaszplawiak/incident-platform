package com.incidentplatform.incident.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * Enables scheduled tasks and ShedLock for incident-service.
 *
 * <p>Added for backlog #36's {@code IncidentEventOutboxScheduler} —
 * incident-service had no scheduled jobs of any kind before this. ShedLock
 * prevents the outbox scheduler from running concurrently across multiple
 * incident-service instances — only one instance publishes a given batch
 * of outbox entries at a time, preventing duplicate Kafka publishes if
 * this service is horizontally scaled.
 *
 * <p>Same setup already established in notification-service's and
 * postmortem-service's {@code ShedLockConfig} — replicated here rather
 * than introducing a different configuration approach.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "5m")
public class ShedLockConfig {

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