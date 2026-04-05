package com.incidentplatform.notification.channel;

import com.incidentplatform.notification.dto.NotificationRequest;

public interface NotificationChannel {

    String channelName();

    void send(NotificationRequest request);

    boolean isEnabled();
}