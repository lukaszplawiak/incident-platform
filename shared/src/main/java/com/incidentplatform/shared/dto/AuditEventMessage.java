package com.incidentplatform.shared.dto;

import com.incidentplatform.shared.audit.ActorType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable audit event message published to Kafka topic {@code audit.events}.
 *
 * <h2>Resource model</h2>
 * {@link #resourceId} identifies the primary domain object affected by the event.
 * {@link #resourceType} tells consumers how to interpret it:
 * <ul>
 *   <li>{@code "INCIDENT"} — {@code resourceId} is an incident UUID</li>
 *   <li>{@code "USER"}     — {@code resourceId} is a user UUID</li>
 * </ul>
 * This replaces the previous {@code incidentId} field which forced all events
 * to carry an incident reference even when none existed (e.g. auth events).
 *
 * <h2>Factory methods</h2>
 * <ul>
 *   <li>{@link #incident(UUID, String, String, String, String, Map)} —
 *       system-initiated incident event</li>
 *   <li>{@link #incidentUser(UUID, String, String, String, String, String, Map)} —
 *       user-initiated incident event</li>
 *   <li>{@link #auth(UUID, String, String, String, String, Map)} —
 *       auth-domain event (login, logout, password change, user management)</li>
 * </ul>
 */
public record AuditEventMessage(
        UUID resourceId,
        String resourceType,
        String tenantId,
        String eventType,
        String actor,
        ActorType actorType,
        String sourceService,
        String detail,
        Map<String, Object> metadata,
        Instant occurredAt
) {

    // ── Incident events ───────────────────────────────────────────────────

    /**
     * System-initiated incident event (e.g. auto-resolve, escalation).
     *
     * @param incidentId the incident UUID
     */
    public static AuditEventMessage incident(UUID incidentId,
                                             String tenantId,
                                             String eventType,
                                             String sourceService,
                                             String detail,
                                             Map<String, Object> metadata) {
        return new AuditEventMessage(
                incidentId,
                "INCIDENT",
                tenantId,
                eventType,
                sourceService,
                ActorType.SYSTEM,
                sourceService,
                detail,
                metadata,
                Instant.now());
    }

    /**
     * User-initiated incident event (e.g. status change, assignment).
     *
     * @param incidentId the incident UUID
     * @param userId     the UUID of the user who performed the action
     */
    public static AuditEventMessage incidentUser(UUID incidentId,
                                                 String tenantId,
                                                 String eventType,
                                                 String sourceService,
                                                 String userId,
                                                 String detail,
                                                 Map<String, Object> metadata) {
        return new AuditEventMessage(
                incidentId,
                "INCIDENT",
                tenantId,
                eventType,
                userId,
                ActorType.USER,
                sourceService,
                detail,
                metadata,
                Instant.now());
    }

    // ── Auth events ───────────────────────────────────────────────────────

    /**
     * Auth-domain event — login, logout, password change, user management.
     *
     * <p>{@code resourceId} is the affected user's UUID.
     * {@code actor} is the UUID of the user who performed the action
     * (same as {@code resourceId} for self-service actions like login/logout,
     * different for admin actions like create/delete user).
     *
     * @param userId the UUID of the affected user
     * @param actor  the UUID of the user who performed the action
     */
    public static AuditEventMessage auth(UUID userId,
                                         String tenantId,
                                         String eventType,
                                         String sourceService,
                                         String actor,
                                         String detail,
                                         Map<String, Object> metadata) {
        return new AuditEventMessage(
                userId,
                "USER",
                tenantId,
                eventType,
                actor,
                ActorType.USER,
                sourceService,
                detail,
                metadata,
                Instant.now());
    }

    /**
     * System-initiated auth event (e.g. account locked by rate limiter).
     *
     * @param userId the UUID of the affected user
     */
    public static AuditEventMessage authSystem(UUID userId,
                                               String tenantId,
                                               String eventType,
                                               String sourceService,
                                               String detail,
                                               Map<String, Object> metadata) {
        return new AuditEventMessage(
                userId,
                "USER",
                tenantId,
                eventType,
                sourceService,
                ActorType.SYSTEM,
                sourceService,
                detail,
                metadata,
                Instant.now());
    }
}