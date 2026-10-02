package com.incidentplatform.shared.audit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code audit.event.unrecorded} counter (alert {@code AuditEventUnrecorded},
 * backlog #0-84): audit events about something already done and irreversible
 * (a notification delivered, an escalation fired) whose write failed and was
 * counted rather than thrown.
 *
 * <p>Each event type a class can count is registered at zero when it is built
 * (found in review): a counter created at its first increment starts its
 * Prometheus series at 1, and {@code increase()} cannot see a rise before a
 * series' first sample, so the alert stayed silent until the second failure
 * of that type. {@code AuthEmailScheduler} registers its counters the same way.
 */
public final class UnrecordedAuditEvents {

    public static final String COUNTER = "audit.event.unrecorded";

    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    /**
     * @param eventTypes every event type the owner may count (each registered
     *                   at zero now)
     */
    public UnrecordedAuditEvents(MeterRegistry meterRegistry, String... eventTypes) {
        this.meterRegistry = meterRegistry;
        for (final String eventType : eventTypes) {
            counters.put(eventType, register(eventType));
        }
    }

    /**
     * Counts one unrecorded event. A type not declared at construction is
     * still counted (registered now), but its first failure is then invisible
     * to the alert: declare every type up front.
     */
    public void increment(String eventType) {
        counters.computeIfAbsent(eventType, this::register).increment();
    }

    private Counter register(String eventType) {
        return Counter.builder(COUNTER)
                .description("Audit events about an action already done that could not be written "
                        + "to the outbox (backlog #0-84)")
                .tag("event_type", eventType)
                .register(meterRegistry);
    }
}
