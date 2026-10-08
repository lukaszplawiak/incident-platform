package com.incidentplatform.notification.scheduler;

import com.incidentplatform.notification.channel.SlackApiClient;
import com.incidentplatform.notification.channel.SlackNotificationChannel;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The shipped {@code application.yml} starts: its processing budget fits its
 * ShedLock together with the worst case of a Slack send (review of backlog
 * #0-103). {@link NotificationScheduler} refuses to start otherwise, and the
 * values come from three places (the scheduler's budget, Slack's timeouts,
 * Resilience4j's {@code slack} retry), so a change to any one of them could
 * break startup without touching the scheduler. The retry is the one
 * Resilience4j's own auto-configuration builds from the file, not a copy of
 * its numbers (second review), and the Slack timeouts are read from the keys
 * the code binds, not left to its defaults.
 */
@DisplayName("NotificationScheduler — application.yml's defaults fit the lock (backlog #0-103)")
class NotificationSchedulerDefaultsTest {

    @Test
    @DisplayName("budget + 30 s margin + Slack's worst-case send (its calls with the real retry) fits the 4-minute lock")
    void shippedDefaultsFitTheLock() {
        new ApplicationContextRunner()
                .withInitializer(context -> onlyApplicationYml(context.getEnvironment()))
                .withConfiguration(AutoConfigurations.of(RetryAutoConfiguration.class))
                .run(context -> {
                    final Binder yml = Binder.get(context.getEnvironment());
                    final Duration connect = yml.bind(
                            "notification.channels.slack.connect-timeout", Duration.class).get();
                    final Duration read = yml.bind(
                            "notification.channels.slack.read-timeout", Duration.class).get();
                    final Duration budget = yml.bind(
                            "notification.scheduler.processing-budget", Duration.class).get();

                    assertThat(connect).isEqualTo(Duration.ofSeconds(3));
                    assertThat(read).isEqualTo(Duration.ofSeconds(5));

                    final Duration call = SlackApiClient.worstCaseCall(context.getBean(RetryRegistry.class)
                            .retry(SlackApiClient.RETRY_NAME).getRetryConfig(), connect, read);
                    // 3 x (3 s + 5 s) + 500 ms + 1 s: the retry really is application.yml's.
                    assertThat(call).isEqualTo(Duration.ofMillis(25_500));

                    final Duration send = call.multipliedBy(SlackNotificationChannel.MAX_CALLS_PER_SEND);
                    assertThatCode(() -> NotificationScheduler.validated(budget, send))
                            .doesNotThrowAnyException();
                });
    }

    /**
     * application.yml alone, every {@code ${VAR:default}} left to its default: no
     * variable of the machine running the test may change what is checked.
     */
    private static void onlyApplicationYml(ConfigurableEnvironment environment) {
        environment.getPropertySources().stream().map(PropertySource::getName).toList()
                .forEach(environment.getPropertySources()::remove);
        try {
            new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"))
                    .forEach(environment.getPropertySources()::addLast);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
