package com.incidentplatform.shared.events;

public sealed interface AlertEvent
        permits AlertReceivedEvent {

    String tenantId();
}
