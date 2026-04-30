package com.incidentplatform.notification.dto;

import com.incidentplatform.shared.domain.Severity;

import java.util.UUID;

public record NotificationRequest(

        UUID incidentId,
        String tenantId,
        String eventType,
        String recipient,
        String subject,
        String message,
        Severity severity,
        String incidentTitle

) {}