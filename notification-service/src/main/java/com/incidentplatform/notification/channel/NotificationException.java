package com.incidentplatform.notification.channel;

public class NotificationException extends RuntimeException {

    private final String channel;
    private final String recipient;

    public NotificationException(String channel, String recipient,
                                 String message, Throwable cause) {
        super(message, cause);
        this.channel = channel;
        this.recipient = recipient;
    }

    public NotificationException(String channel, String recipient,
                                 String message) {
        super(message);
        this.channel = channel;
        this.recipient = recipient;
    }

    public String getChannel() { return channel; }
    public String getRecipient() { return recipient; }
}