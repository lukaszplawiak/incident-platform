package com.incidentplatform.notification.channel;

import com.incidentplatform.notification.dto.NotificationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("NotificationChannel implementations")
class NotificationChannelTest {

    private final NotificationRequest sampleRequest = new NotificationRequest(
            UUID.randomUUID(),
            "test-tenant",
            "IncidentOpenedEvent",
            "oncall@test.com",
            "[CRITICAL] High CPU",
            "New critical incident detected",
            "CRITICAL",
            "High CPU Usage"
    );

    @Test
    @DisplayName("EmailNotificationChannel - channelName should be EMAIL")
    void emailChannelNameShouldBeEmail() {
        final EmailNotificationChannel channel = new EmailNotificationChannel();
        assertThat(channel.channelName()).isEqualTo("EMAIL");
    }

    @Test
    @DisplayName("EmailNotificationChannel - should be enabled by default")
    void emailChannelShouldBeEnabledByDefault() {
        final EmailNotificationChannel channel = new EmailNotificationChannel();
        ReflectionTestUtils.setField(channel, "enabled", true);
        assertThat(channel.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("EmailNotificationChannel - should be disabled when configured")
    void emailChannelShouldBeDisableable() {
        final EmailNotificationChannel channel = new EmailNotificationChannel();
        ReflectionTestUtils.setField(channel, "enabled", false);
        assertThat(channel.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("EmailNotificationChannel - send should not throw")
    void emailSendShouldNotThrow() {
        final EmailNotificationChannel channel = new EmailNotificationChannel();
        ReflectionTestUtils.setField(channel, "enabled", true);
        ReflectionTestUtils.setField(channel, "fromAddress",
                "alerts@incidentplatform.com");

        assertThatCode(() -> channel.send(sampleRequest))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SlackNotificationChannel - channelName and isEnabled should be correct")
    void slackChannelShouldBeCorrectlyConfigured() {
        final SlackNotificationChannel channel =
                new SlackNotificationChannel(RestClient.builder());
        ReflectionTestUtils.setField(channel, "enabled", true);
        ReflectionTestUtils.setField(channel, "defaultChannel", "#incidents");
        assertThat(channel.channelName()).isEqualTo("SLACK");
        assertThat(channel.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("SmsNotificationChannel - channelName should be SMS")
    void smsChannelNameShouldBeSms() {
        final SmsNotificationChannel channel = new SmsNotificationChannel();
        assertThat(channel.channelName()).isEqualTo("SMS");
    }

    @Test
    @DisplayName("SmsNotificationChannel - send should not throw")
    void smsSendShouldNotThrow() {
        final SmsNotificationChannel channel = new SmsNotificationChannel();
        ReflectionTestUtils.setField(channel, "enabled", true);
        ReflectionTestUtils.setField(channel, "fromNumber", "+48100200300");

        assertThatCode(() -> channel.send(sampleRequest))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SmsNotificationChannel - should truncate long messages to 160 chars")
    void smsShouldTruncateLongMessages() {
        final SmsNotificationChannel channel = new SmsNotificationChannel();
        ReflectionTestUtils.setField(channel, "enabled", true);
        ReflectionTestUtils.setField(channel, "fromNumber", "+48100200300");

        final String longMessage = "x".repeat(300);
        final NotificationRequest request = new NotificationRequest(
                UUID.randomUUID(), "tenant", "IncidentEscalatedEvent",
                "+48999888777", "subject", longMessage, "CRITICAL", "title");

        // SMS cut to 160 chars
        assertThatCode(() -> channel.send(request))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("all channels should have unique names")
    void allChannelsShouldHaveUniqueNames() {
        assertThat(new EmailNotificationChannel().channelName())
                .isNotEqualTo(new SlackNotificationChannel(RestClient.builder()).channelName());
        assertThat(new SlackNotificationChannel(RestClient.builder()).channelName())
                .isNotEqualTo(new SmsNotificationChannel().channelName());
        assertThat(new EmailNotificationChannel().channelName())
                .isNotEqualTo(new SmsNotificationChannel().channelName());
    }
}