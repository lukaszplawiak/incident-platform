package com.incidentplatform.auth.config;

import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Scheduling is off exactly when auth-service runs as the break-glass command
 * (backlog #0-88), and the lock provider stays either way. Found missing in
 * review: only the condition itself was tested, so moving or dropping the
 * {@code @Conditional} on {@code SchedulerConfig.Scheduling} would have
 * turned no test red.
 */
@DisplayName("SchedulerConfig")
class SchedulerConfigTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withUserConfiguration(SchedulerConfig.class);

    @Test
    @DisplayName("the service schedules its jobs")
    void serviceSchedules() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(LockProvider.class);
            assertThat(context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                    .isTrue();
        });
    }

    @Test
    @DisplayName("the break-glass command schedules nothing, so its exit leaves no ShedLock lock held")
    void commandSchedulesNothing() {
        contexts.withInitializer(com.incidentplatform.auth.breakglass.BreakGlassCommandTestSupport::markAsCommand)
                .run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(LockProvider.class);
            assertThat(context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                    .isFalse();
        });
    }
}
