package com.incidentplatform.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Binds {@link InviteEmailProperties} from the real {@code application.yml}
 * (backlog #0-52, code review): {@code retry-backoff} is a
 * {@code List<Duration>} read from one comma-separated placeholder default,
 * a form nothing else in this codebase uses, and every other test builds the
 * record directly and so bypasses the binder.
 */
@DisplayName("InviteEmailProperties — binding from application.yml")
class InviteEmailPropertiesBindingTest {

    private static InviteEmailProperties bind(Map<String, Object> overrides) throws IOException {
        final StandardEnvironment environment = new StandardEnvironment();
        // Isolate from the machine's own INVITE_EMAIL_* variables.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("overrides", overrides));
        final List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        yaml.forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment).bind("invite.email", InviteEmailProperties.class).get();
    }

    @Test
    @DisplayName("defaults: backoff 1m, 5m, 30m, 2h, 6h, a 2 minute budget, 30 days retention")
    void bindsDefaults() throws IOException {
        final InviteEmailProperties properties = bind(Map.of());

        assertThat(properties.retryBackoff()).containsExactly(
                Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
                Duration.ofHours(2), Duration.ofHours(6));
        assertThat(properties.processingBudget()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.retention()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.batchSize()).isEqualTo(15);
        assertThat(properties.schedulerIntervalMs()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("environment variables override them, the backoff in the same comma-separated form")
    void bindsEnvironmentOverride() throws IOException {
        final InviteEmailProperties properties = bind(Map.of(
                "INVITE_EMAIL_RETRY_BACKOFF", "PT10S,PT1H",
                "INVITE_EMAIL_PROCESSING_BUDGET", "PT1M",
                "INVITE_EMAIL_RETENTION", "P7D"));

        assertThat(properties.retryBackoff()).containsExactly(Duration.ofSeconds(10), Duration.ofHours(1));
        assertThat(properties.processingBudget()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.retention()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("a non-positive backoff step fails binding, so the service does not start")
    void rejectsNonPositiveStep() {
        assertThatThrownBy(() -> bind(Map.of("INVITE_EMAIL_RETRY_BACKOFF", "PT1M,PT0S")))
                .isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a zero retention fails binding")
    void rejectsZeroRetention() {
        assertThatThrownBy(() -> bind(Map.of("INVITE_EMAIL_RETENTION", "PT0S")))
                .isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
