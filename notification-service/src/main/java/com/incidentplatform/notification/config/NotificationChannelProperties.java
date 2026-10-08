package com.incidentplatform.notification.config;

import java.time.Duration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly-typed, validated configuration for notification channels and the operator alert address.
 *
 * <p>Replaces {@code @Value} injections across four classes:
 * <ul>
 *   <li>{@code EmailNotificationChannel}: {@code notification.channels.email.enabled},
 *       {@code notification.channels.email.from}</li>
 *   <li>{@code SlackNotificationChannel}: {@code notification.channels.slack.enabled}; {@code SlackApiClient}:
 *       {@code api-base-url}, {@code connect-timeout}, {@code read-timeout} (backlog #0-103)</li>
 *   <li>{@code SlackSignatureVerifier}: {@code notification.channels.slack.signing-secret}</li>
 *   <li>{@code SmsNotificationChannel}: {@code notification.channels.sms.enabled},
 *       {@code notification.channels.sms.from-number}</li>
 *   <li>{@code OperatorAlertService}: {@code notification.operator-alert.email}</li>
 * </ul>
 *
 * <h2>Nested records</h2>
 * Each channel group and the operator alert group is a nested record. This mirrors the
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
 *       signing-secret: ${SLACK_SIGNING_SECRET}
 *       api-base-url: ${SLACK_API_BASE_URL:https://slack.com/api}
 *       connect-timeout: ${SLACK_CONNECT_TIMEOUT:3s}
 *       read-timeout: ${SLACK_READ_TIMEOUT:5s}
 *     sms:
 *       enabled: true
 *       from-number: ${SMS_FROM:+1234567890}
 *   operator-alert:
 *     email: ${NOTIFICATION_OPERATOR_ALERT_EMAIL:}
 * }</pre>
 *
 * <h2>Fixed (backlog #0-18): no platform-wide destination for tenant content</h2>
 * {@code notification.fallback.*} (email, Slack channel, phone) used to receive the
 * incident text whenever no on-call contact was found, for every tenant. It is gone.
 * {@link OperatorAlert} replaces it: an address that belongs to the platform operator
 * and receives only content-free alerts (tenant id, incident id, event type, reason).
 * It has no default, so an unconfigured deployment sends no email rather than sending
 * one to a placeholder such as {@code oncall@example.com}.
 *
 * <h2>Fixed (backlog #0-21): bot-token/channel/broadcast-enabled are no longer global</h2>
 * {@code Slack} used to carry the platform's one bot token, one channel and one
 * broadcast flag — a single-organisation design (see {@code SlackNotificationChannel}'s
 * own Javadoc). Each tenant's own bot token, default channel and broadcast flag now live
 * in auth-service's {@code SlackWorkspace} and are read per notification via {@code
 * SlackWorkspaceClient}. {@code signing-secret} and {@code api-base-url} stay global here —
 * verified against Slack's own docs that the signing secret is per-App, not per-workspace.
 */
@ConfigurationProperties(prefix = "notification")
@Validated
public record NotificationChannelProperties(

        @NotNull @Valid
        Channels channels,

        @Valid
        OperatorAlert operatorAlert

) {

    public NotificationChannelProperties {
        operatorAlert = operatorAlert != null ? operatorAlert : new OperatorAlert(null, null);
    }

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

            @NotBlank(message = "notification.channels.slack.signing-secret must not be blank")
            String signingSecret,

            // Configurable rather than hardcoded to https://slack.com/api —
            // lets tests point SlackNotificationChannel at a local WireMock
            // server instead of needing to reach the real Slack API (or,
            // previously, being unable to test the HTTP call at all).
            // Defaults to the real Slack API in application.yml.
            @NotBlank(message = "notification.channels.slack.api-base-url must not be blank")
            String apiBaseUrl,

            // Backlog #0-103: SlackApiClient's own timeouts. The client had none
            // (the bare RestClient.Builder sets no read timeout), so a Slack that
            // never answered held the scheduler's thread and was never retried.
            // Apart from notification.client.*, which are for calls inside the
            // cluster. Absent means the defaults below; zero or negative is
            // rejected at startup, as it would mean no timeout at all.
            Duration connectTimeout,
            Duration readTimeout
    ) {

        public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
        public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);

        public Slack {
            connectTimeout = positive(connectTimeout, DEFAULT_CONNECT_TIMEOUT,
                    "notification.channels.slack.connect-timeout");
            readTimeout = positive(readTimeout, DEFAULT_READ_TIMEOUT,
                    "notification.channels.slack.read-timeout");
        }

        private static Duration positive(Duration value, Duration fallback, String key) {
            final Duration resolved = value != null ? value : fallback;
            if (resolved.isZero() || resolved.isNegative()) {
                throw new IllegalArgumentException(key + " must be positive");
            }
            return resolved;
        }
    }

    public record Sms(
            boolean enabled,

            @NotBlank(message = "notification.channels.sms.from-number must not be blank")
            String fromNumber
    ) {}

    /**
     * Where the platform tells its <em>operator</em> that a notification could
     * not be delivered to anyone in the tenant (backlog #0-18). The address
     * belongs to the operator, never to a tenant, and receives no tenant
     * content. Blank or absent means no email is sent; the ERROR log and the
     * {@code notification.undeliverable} metric remain.
     */
    public record OperatorAlert(
            // Blank is allowed (no email is sent); a non-blank value must be an address, so a
            // typo fails at startup instead of only in the log when the first alert is sent.
            // Fully qualified: the nested Email record above would shadow the annotation.
            @jakarta.validation.constraints.Email(
                    message = "notification.operator-alert.email must be an email address")
            String email,

            // At most one alert email per tenant and reason within this interval. Absent means
            // PT15M; zero or negative is rejected at startup, because it would silently switch
            // the limit off.
            Duration minInterval
    ) {

        public static final Duration DEFAULT_MIN_INTERVAL = Duration.ofMinutes(15);

        public OperatorAlert {
            minInterval = minInterval != null ? minInterval : DEFAULT_MIN_INTERVAL;
            if (minInterval.isZero() || minInterval.isNegative()) {
                throw new IllegalArgumentException(
                        "notification.operator-alert.min-interval must be positive");
            }
        }

        public boolean hasEmail() {
            return email != null && !email.isBlank();
        }
    }
}
