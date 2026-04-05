package com.incidentplatform.notification.service;

import com.incidentplatform.notification.channel.NotificationException;
import com.incidentplatform.notification.domain.NotificationLog;
import com.incidentplatform.notification.repository.NotificationLogRepository;
import com.incidentplatform.notification.router.NotificationRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class NotificationService {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRouter router;
    private final NotificationLogRepository logRepository;

    public NotificationService(NotificationRouter router,
                               NotificationLogRepository logRepository) {
        this.router = router;
        this.logRepository = logRepository;
    }

    public void processEvent(String eventType,
                             UUID incidentId,
                             String tenantId,
                             String severity,
                             String title) {

        log.info("Processing notification event: type={}, incidentId={}, " +
                        "severity={}, tenant={}",
                eventType, incidentId, severity, tenantId);

        final var channelRequests = router.route(
                eventType, incidentId, tenantId, severity, title);

        if (channelRequests.isEmpty()) {
            log.debug("No notifications to send for event: {}", eventType);
            return;
        }

        for (final var channelRequest : channelRequests) {
            final var channel = channelRequest.channel();
            final var request = channelRequest.request();

            try {
                channel.send(request);

                logRepository.save(NotificationLog.sent(
                        incidentId,
                        tenantId,
                        eventType,
                        channel.channelName(),
                        request.recipient(),
                        request.subject(),
                        request.message()
                ));

                log.info("Notification sent: channel={}, recipient={}, " +
                                "incidentId={}, tenant={}",
                        channel.channelName(), request.recipient(),
                        incidentId, tenantId);

            } catch (NotificationException e) {
                logRepository.save(NotificationLog.failed(
                        incidentId,
                        tenantId,
                        eventType,
                        channel.channelName(),
                        request.recipient(),
                        e.getMessage()
                ));

                log.error("Notification failed: channel={}, recipient={}, " +
                                "incidentId={}, error={}",
                        channel.channelName(), request.recipient(),
                        incidentId, e.getMessage());

            } catch (Exception e) {
                logRepository.save(NotificationLog.failed(
                        incidentId,
                        tenantId,
                        eventType,
                        channel.channelName(),
                        request.recipient(),
                        "Unexpected error: " + e.getMessage()
                ));

                log.error("Unexpected error sending notification: " +
                                "channel={}, incidentId={}",
                        channel.channelName(), incidentId, e);
            }
        }

        log.info("Notification processing complete: eventType={}, " +
                        "channels={}, incidentId={}, tenant={}",
                eventType, channelRequests.size(), incidentId, tenantId);
    }
}