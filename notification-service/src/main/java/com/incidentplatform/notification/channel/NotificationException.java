package com.incidentplatform.notification.channel;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A channel could not deliver a notification.
 *
 * <h2>Changed (backlog #0-93): the message is the platform's, never a provider's</h2>
 * The message used to be free text that the email and Slack channels built by
 * appending the mail library's or the Slack client's message, and it was stored
 * as it was in {@code notification_log} and the tenant's audit trail. Now the
 * exception carries a {@link NotificationFailureReason} and at most a short
 * provider code ({@link #detail()}: Slack's {@code error}, or an HTTP status),
 * kept only if it is a plain code ({@code [a-z0-9_]}, up to 64 characters), so
 * nothing a provider wrote can reach a row as text. {@link #getMessage()} is
 * built from those alone ({@link #recordedText()}), so even code that logs or
 * stores the message gets the platform's words. The provider's own error stays
 * in {@link #getCause()}, for the log line written where this is caught.
 */
public class NotificationException extends RuntimeException {

    private static final Pattern PROVIDER_CODE = Pattern.compile("[a-z0-9_]{1,64}");

    private final String channel;
    private final String recipient;
    private final NotificationFailureReason reason;
    private final String detail;

    /**
     * @param detail a provider's code (Slack's {@code error}, {@code http_403});
     *               dropped unless it is a plain code, may be {@code null}
     * @param cause  the provider's own failure, logged where this is caught;
     *               may be {@code null}
     */
    public NotificationException(String channel, String recipient,
                                 NotificationFailureReason reason, String detail,
                                 Throwable cause) {
        super(text(Objects.requireNonNull(reason, "reason"), checked(detail)), cause);
        this.channel = channel;
        this.recipient = recipient;
        this.reason = reason;
        this.detail = checked(detail);
    }

    public NotificationException(String channel, String recipient,
                                 NotificationFailureReason reason, Throwable cause) {
        this(channel, recipient, reason, null, cause);
    }

    public String getChannel() { return channel; }
    public String getRecipient() { return recipient; }

    public NotificationFailureReason reason() { return reason; }

    /** The provider's code, checked to be a plain code; {@code null} when there is none. */
    public String detail() { return detail; }

    /**
     * What is stored and shown for this failure, e.g.
     * {@code "SLACK_REJECTED (not_in_channel): Slack refused the message"}.
     */
    public String recordedText() {
        return getMessage();
    }

    private static String checked(String detail) {
        return detail != null && PROVIDER_CODE.matcher(detail).matches() ? detail : null;
    }

    private static String text(NotificationFailureReason reason, String detail) {
        return reason.name() + (detail != null ? " (" + detail + ")" : "") + ": " + reason.description();
    }
}
