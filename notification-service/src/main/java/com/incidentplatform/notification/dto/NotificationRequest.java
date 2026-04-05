package com.incidentplatform.notification.dto;

import java.util.UUID;

public record NotificationRequest(

        UUID incidentId,
        String tenantId,
        String eventType,
        String recipient,
        String subject,
        String message,

        String severity,

        String incidentTitle

) {}