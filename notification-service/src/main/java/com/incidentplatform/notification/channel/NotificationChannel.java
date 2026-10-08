package com.incidentplatform.notification.channel;

import com.incidentplatform.notification.dto.NotificationRequest;

import java.time.Duration;

public interface NotificationChannel {

    String channelName();

    void send(NotificationRequest request);

    boolean isEnabled();

    /**
     * How long one {@link #send} can take at most beyond what {@code
     * NotificationScheduler}'s fixed margin for the entry in flight covers,
     * checked against its ShedLock at startup (review of backlog #0-103).
     * Zero for a channel whose send stays within that margin; a channel that
     * retries or waits long on a remote call says how long.
     */
    default Duration worstCaseSendTime() {
        return Duration.ZERO;
    }
}
