package com.incidentplatform.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * {@code platform.mfa-recovery.*} from application.yml (backlog #0-90): the
 * waiting period defaults to 72 h and must be between 24 h and 7 days, so
 * the notice's cancel link (14 days) outlives it; a wrong value stops startup
 * rather than shortening the account's chance to object.
 */
@DisplayName("MfaRecoveryProperties — binding from application.yml")
class MfaRecoveryPropertiesTest {

    private static MfaRecoveryProperties bind(Map<String, Object> overrides) throws IOException {
        final StandardEnvironment environment = new StandardEnvironment();
        // Isolate from the machine's own PLATFORM_MFA_RECOVERY_* variables.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("overrides", overrides));
        final List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        yaml.forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment).bind("platform.mfa-recovery", MfaRecoveryProperties.class).get();
    }

    @Test
    @DisplayName("defaults: 72 h waiting period, a run every 5 minutes, 20 requests a run")
    void bindsDefaults() throws IOException {
        assertThat(bind(Map.of())).isEqualTo(new MfaRecoveryProperties(Duration.ofHours(72), 300_000L, 20));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "-PT1H", "PT1S", "PT23H59M", "P8D"})
    @DisplayName("a waiting period under 24 h or over 7 days is refused (security review: no shrinking it to seconds)")
    void invalidWaitingPeriodRefused(String waitingPeriod) {
        assertThatThrownBy(() -> bind(Map.of("platform.mfa-recovery.waiting-period", waitingPeriod)))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("waiting-period");
    }

    @Test
    @DisplayName("exactly 24 h is allowed")
    void twentyFourHoursAllowed() throws IOException {
        assertThat(bind(Map.of("platform.mfa-recovery.waiting-period", "PT24H")).waitingPeriod())
                .isEqualTo(MfaRecoveryProperties.MIN_WAITING_PERIOD);
    }

    @Test
    @DisplayName("exactly 7 days is allowed")
    void sevenDaysAllowed() throws IOException {
        assertThat(bind(Map.of("platform.mfa-recovery.waiting-period", "P7D")).waitingPeriod())
                .isEqualTo(MfaRecoveryProperties.MAX_WAITING_PERIOD);
    }
}
