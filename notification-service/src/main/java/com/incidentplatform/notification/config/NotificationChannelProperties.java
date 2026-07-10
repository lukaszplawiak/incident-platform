package com.incidentplatform.notification.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly-typed, validated configuration for notification channels and fallback addresses.
 *
 * <p>Replaces seven {@code @Value} injections across four classes:
 * <ul>
 *   <li>{@code EmailNotificationChannel}: {@code notification.channels.email.enabled},
 *       {@code notification.channels.email.from}</li>
 *   <li>{@code SlackNotificationChannel}: {@code notification.channels.slack.enabled},
 *       {@code notification.channels.slack.bot-token},
 *       {@code notification.channels.slack.channel}</li>
 *   <li>{@code SlackSignatureVerifier}: {@code notification.channels.slack.signing-secret}</li>
 *   <li>{@code SmsNotificationChannel}: {@code notification.channels.sms.enabled},
 *       {@code notification.channels.sms.from-number}</li>
 *   <li>{@code NotificationRouter}: {@code notification.fallback.email},
 *       {@code notification.fallback.slack-channel},
 *       {@code notification.fallback.phone}</li>
 * </ul>
 *
 * <h2>Nested records</h2>
 * Each channel group and fallback group is a nested record. This mirrors the
 * YAML hierarchy and keeps related properties co-located.
 *
 * <h2>YAML configuration</h2>
 * <pre>{@code
 * notification:
 *   channels:
 *     email:
 *       enabled: true
 *       from: ${NOTIFICATION_EMAIL_FROM:alerts@incidentplatform.com}
 *     slack:
 *       enabled: true
 *       bot-token: ${SLACK_BOT_TOKEN}
 *       channel: ${SLACK_CHANNEL:#incidents}
 *       signing-secret: ${SLACK_SIGNING_SECRET}
 *     sms:
 *       enabled: true
 *       from-number: ${SMS_FROM:+1234567890}
 *   fallback:
 *     email: ${NOTIFICATION_FALLBACK_EMAIL:oncall@example.com}
 *     slack-channel: ${NOTIFICATION_FALLBACK_SLACK:#incidents}
 *     phone: ${NOTIFICATION_FALLBACK_PHONE:}
 * }</pre>
 */
@ConfigurationProperties(prefix = "notification")
@Validated
public record NotificationChannelProperties(

        @NotNull @Valid
        Channels channels,

        @NotNull @Valid
        Fallback fallback

) {

    public record Channels(
            @NotNull @Valid Email email,
            @NotNull @Valid Slack slack,
            @NotNull @Valid Sms sms
    ) {}

    public record Email(
            boolean enabled,

            @NotBlank(message = "notification.channels.email.from must not be blank")
            String from
    ) {}

    public record Slack(
            boolean enabled,

            @NotBlank(message = "notification.channels.slack.bot-token must not be blank")
            String botToken,

            @NotBlank(message = "notification.channels.slack.channel must not be blank")
            String channel,

            @NotBlank(message = "notification.channels.slack.signing-secret must not be blank")
            String signingSecret
    ) {}

    public record Sms(
            boolean enabled,

            @NotBlank(message = "notification.channels.sms.from-number must not be blank")
            String fromNumber
    ) {}

    public record Fallback(
            @NotBlank(message = "notification.fallback.email must not be blank")
            String email,

            @NotBlank(message = "notification.fallback.slack-channel must not be blank")
            String slackChannel,

            // Phone is optional — empty string means SMS fallback is disabled
            String phone
    ) {}
}