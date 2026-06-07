package com.incidentplatform.notification.channel;

import com.incidentplatform.notification.dto.NotificationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SmsNotificationChannel implements NotificationChannel {

    private static final Logger log =
            LoggerFactory.getLogger(SmsNotificationChannel.class);

    private final boolean enabled;
    private final String fromNumber;

    public SmsNotificationChannel(
            @Value("${notification.channels.sms.enabled:true}") boolean enabled,
            @Value("${notification.channels.sms.from-number:+1234567890}") String fromNumber) {
        this.enabled = enabled;
        this.fromNumber = fromNumber;
    }

    @Override
    public String channelName() {
        return "SMS";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void send(NotificationRequest request) {
        log.info("""
                [SMS SIMULATION] Sending SMS:
                  From: {}
                  To:   {}
                  Text: {}
                  Incident: {} | Severity: {} | Tenant: {}
                """,
                fromNumber,
                request.recipient(),
                request.message() != null
                        ? request.message().substring(0, Math.min(160, request.message().length()))
                        : "",
                request.incidentId(),
                request.severity(),
                request.tenantId()
        );
    }
}